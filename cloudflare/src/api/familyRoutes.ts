/**
 * Family group endpoints: groups, invites, members, messages, live locations
 * and check-ins.
 *
 * Authorisation is enforced on every route: a caller must hold an active
 * membership row for the group (never a client-supplied identity), which closes
 * the IDOR class of attacks that a naive `?groupId=` API would allow.
 */

import { randomId, sha256Hex } from "../util/crypto";
import { conflict, forbidden, notFound, unauthorized } from "../util/errors";
import {
  optionalNumber,
  requireEnum,
  requireLatitude,
  requireLongitude,
  requireNumber,
  requireString,
  asRecord,
  optionalString,
} from "../util/validate";
import { enforceRateLimit, RATE_LIMITS } from "../util/ratelimit";
import {
  addMember,
  consumeInvite,
  createGroup,
  createInvite,
  findInviteByHash,
  getGroup,
  getMembership,
  listGroupsForUser,
  listMembers,
  MANAGER_ROLES,
  removeMember,
  updateMemberRole,
  type FamilyRole,
} from "../db/family";
import { listFamilyMessages, insertFamilyMessage, completeCheckin, insertCheckin, listCheckins } from "../db/familyFeed";
import { latestSharesForGroup, recordLocationShare } from "../db/locations";
import { presenceForUsers } from "../db/presence";
import { recordAudit } from "../db/audit";
import { notifyGroup } from "../safety/broadcast";
import { dataResponse, emptyResponse, readJsonBody, type RequestContext, type Router } from "../http";

const INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const INVITE_GROUPS = 3;
const INVITE_GROUP_LENGTH = 4;

function generateInviteCode(): string {
  const alphabet = INVITE_ALPHABET;
  let code = "";
  for (let group = 0; group < INVITE_GROUPS; group += 1) {
    for (let index = 0; index < INVITE_GROUP_LENGTH; index += 1) {
      const random = new Uint8Array(1);
      crypto.getRandomValues(random);
      code += alphabet[(random[0] as number) % alphabet.length];
    }
    if (group < INVITE_GROUPS - 1) code += "-";
  }
  return code;
}

function normalizeInviteCode(raw: string): string {
  return raw.trim().toUpperCase().replace(/\s+/g, "");
}

async function requireMembership(context: RequestContext, groupId: string, roles?: readonly FamilyRole[]) {
  const auth = context.auth;
  if (!auth) throw unauthorized();
  const membership = await getMembership(context.env, groupId, auth.userId);
  if (!membership) throw forbidden("not_a_member", "You are not a member of this family group.");
  if (roles && !roles.includes(membership.role)) {
    throw forbidden("insufficient_role", "Your family role does not allow this action.");
  }
  return membership;
}

export function registerFamilyRoutes(router: Router): void {
  router.post(
    "/v1/family/groups",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const { env, config, request } = context;
      const body = asRecord(await readJsonBody(request));
      const name = requireString(body.name, "name", { min: 1, max: 60 });
      const now = Date.now();

      const groups = await listGroupsForUser(env, auth.userId);
      if (groups.length >= 5) throw conflict("group_limit_reached", "A maximum of 5 family groups is supported.");

      const group = await createGroup(env, {
        id: randomId(),
        name,
        ownerUserId: auth.userId,
        membershipId: randomId(),
        now,
      });

      await recordAudit(env, config, {
        userId: auth.userId,
        action: "family.group_created",
        outcome: "SUCCESS",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: `group=${group.id}`,
        now,
      });

      return dataResponse({ group: { id: group.id, name: group.name, myRole: "OWNER", createdAt: group.created_at } }, 201);
    },
    { auth: true },
  );

  router.get(
    "/v1/family/groups",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const groups = await listGroupsForUser(context.env, auth.userId);
      return dataResponse({ groups });
    },
    { auth: true },
  );

  router.post(
    "/v1/family/groups/:groupId/invites",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      const body = asRecord(await readJsonBody(context.request));
      const role = requireEnum(body.role, "role", ["PARENT", "GUARDIAN", "CHILD", "MEMBER"] as const);
      const expiresInMinutes = optionalNumber(body.expiresInMinutes, "expiresInMinutes", { min: 5, max: 1440 }) ?? 60;

      await requireMembership(context, groupId, MANAGER_ROLES);

      const code = generateInviteCode();
      const now = Date.now();
      const expiresAt = now + expiresInMinutes * 60_000;
      await createInvite(context.env, {
        id: randomId(),
        groupId,
        codeHash: await sha256Hex(`guardian-invite:${code}`),
        role,
        createdBy: auth.userId,
        now,
        expiresAt,
      });

      // The plaintext code is returned exactly once; only its hash is stored.
      return dataResponse({ invite: { code, role, expiresAt } }, 201);
    },
    { auth: true },
  );

  router.post(
    "/v1/family/join",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const { env, request } = context;
      const body = asRecord(await readJsonBody(request));
      const rawCode = requireString(body.inviteCode, "inviteCode", { min: 4, max: 32 });
      const code = normalizeInviteCode(rawCode);

      await enforceRateLimit(env.DB, `family:join:${auth.userId}`, { limit: 10, windowSeconds: 3600 });

      const now = Date.now();
      const invite = await findInviteByHash(env, await sha256Hex(`guardian-invite:${code}`), now);
      if (!invite) throw notFound("invite_invalid", "Invite code is invalid or has expired.");

      const existing = await getMembership(env, invite.group_id, auth.userId);
      if (existing) throw conflict("already_member", "You are already a member of this family group.");

      const consumed = await consumeInvite(env, invite.id, auth.userId, now);
      if (!consumed) throw notFound("invite_invalid", "Invite code is invalid or has expired.");

      await addMember(env, {
        id: randomId(),
        groupId: invite.group_id,
        userId: auth.userId,
        role: invite.role,
        now,
      });

      await notifyGroup(env, invite.group_id, {
        event: {
          kind: "PRESENCE",
          at: now,
          data: { userId: auth.userId, status: "ONLINE", joinedGroup: true },
        },
      });

      const group = await getGroup(env, invite.group_id);
      return dataResponse(
        { group: group ? { id: group.id, name: group.name, myRole: invite.role, createdAt: group.created_at } : null },
        201,
      );
    },
    { auth: true },
  );

  router.get(
    "/v1/family/groups/:groupId/members",
    async (context) => {
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      await requireMembership(context, groupId);

      const members = await listMembers(context.env, groupId);
      const now = Date.now();
      const presence = await presenceForUsers(
        context.env,
        members.map((member) => member.userId),
        now,
      );
      const presenceByUser = new Map(presence.map((entry) => [entry.userId, entry]));

      return dataResponse({
        members: members.map((member) => {
          const entry = presenceByUser.get(member.userId);
          return {
            ...member,
            online: entry ? entry.status === "ONLINE" && !entry.stale : false,
            lastSeenAt: entry?.lastSeenAt ?? null,
            batteryLevel: entry?.batteryLevel ?? null,
          };
        }),
      });
    },
    { auth: true },
  );

  router.patch(
    "/v1/family/groups/:groupId/members/:memberId",
    async (context) => {
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      const memberId = requireString(context.params.memberId, "memberId", { max: 64, trim: false });
      const body = asRecord(await readJsonBody(context.request));
      const role = requireEnum(body.role, "role", ["PARENT", "GUARDIAN", "CHILD", "MEMBER"] as const);

      const membership = await requireMembership(context, groupId, MANAGER_ROLES);
      const group = await getGroup(context.env, groupId);
      if (!group) throw notFound("group_not_found", "Family group not found.");

      const members = await listMembers(context.env, groupId);
      const target = members.find((member) => member.memberId === memberId);
      if (!target) throw notFound("member_not_found", "Family member not found.");
      if (target.role === "OWNER") throw forbidden("owner_role_locked", "The owner role cannot be changed.");
      if (target.memberId === membership.id) throw forbidden("self_role_change", "You cannot change your own role.");

      const updated = await updateMemberRole(context.env, groupId, memberId, role);
      if (!updated) throw notFound("member_not_found", "Family member not found.");
      return dataResponse({ memberId, role });
    },
    { auth: true },
  );

  router.delete(
    "/v1/family/groups/:groupId/members/:memberId",
    async (context) => {
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      const memberId = requireString(context.params.memberId, "memberId", { max: 64, trim: false });

      const membership = await requireMembership(context, groupId);
      const group = await getGroup(context.env, groupId);
      if (!group) throw notFound("group_not_found", "Family group not found.");

      const members = await listMembers(context.env, groupId);
      const target = members.find((member) => member.memberId === memberId);
      if (!target) throw notFound("member_not_found", "Family member not found.");
      if (target.role === "OWNER") throw forbidden("owner_removal_denied", "The group owner cannot be removed.");

      const removingSelf = target.userId === membership.user_id;
      if (!removingSelf && !MANAGER_ROLES.includes(membership.role)) {
        throw forbidden("insufficient_role", "Your family role does not allow removing members.");
      }

      const removed = await removeMember(context.env, groupId, memberId, Date.now());
      if (!removed) throw notFound("member_not_found", "Family member not found.");

      await notifyGroup(context.env, groupId, {
        event: { kind: "PRESENCE", at: Date.now(), data: { userId: target.userId, status: "OFFLINE", removed: true } },
      });

      return emptyResponse(204);
    },
    { auth: true },
  );

  router.get(
    "/v1/family/groups/:groupId/messages",
    async (context) => {
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      await requireMembership(context, groupId);

      const sinceParam = context.url.searchParams.get("since");
      const since = sinceParam ? Number.parseInt(sinceParam, 10) : null;
      const messages = await listFamilyMessages(context.env, groupId, Number.isFinite(since) ? since : null);
      return dataResponse({ messages });
    },
    { auth: true },
  );

  router.post(
    "/v1/family/groups/:groupId/messages",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      const body = asRecord(await readJsonBody(context.request));
      const text = requireString(body.body, "body", { min: 1, max: 2000 });
      const isEmergency = body.isEmergency === true;

      await requireMembership(context, groupId);
      await enforceRateLimit(context.env.DB, `family:message:${auth.userId}`, RATE_LIMITS.readPerUser);

      const now = Date.now();
      const message = await insertFamilyMessage(context.env, {
        id: randomId(),
        groupId,
        senderUserId: auth.userId,
        body: text,
        isEmergency,
        now,
      });

      await notifyGroup(context.env, groupId, {
        event: {
          kind: "FAMILY_MESSAGE",
          at: now,
          data: {
            id: message.id,
            groupId,
            senderUserId: auth.userId,
            body: message.body,
            isEmergency,
            createdAt: now,
          },
        },
      });

      return dataResponse({ message: { ...message, isEmergency, createdAt: now } }, 201);
    },
    { auth: true },
  );

  router.get(
    "/v1/family/groups/:groupId/locations",
    async (context) => {
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      await requireMembership(context, groupId);
      const shares = await latestSharesForGroup(context.env, groupId, Date.now());
      return dataResponse({ locations: shares });
    },
    { auth: true },
  );

  router.post(
    "/v1/family/groups/:groupId/locations",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });
      const body = asRecord(await readJsonBody(context.request));
      const latitude = requireLatitude(body.latitude);
      const longitude = requireLongitude(body.longitude);
      const accuracyM = optionalNumber(body.accuracyM, "accuracyM", { min: 0, max: 100_000 });
      const batteryLevel = optionalNumber(body.batteryLevel, "batteryLevel", { min: 0, max: 100 });
      const source = requireEnum(body.source, "source", ["CHECKIN", "SAFE_ZONE", "MANUAL"] as const);
      const ttlMinutes = optionalNumber(body.ttlMinutes, "ttlMinutes", { min: 1, max: 1440 }) ?? 30;

      await requireMembership(context, groupId);
      await enforceRateLimit(context.env.DB, `location:${auth.userId}`, {
        limit: 120,
        windowSeconds: 3600,
      });

      const now = Date.now();
      const share = await recordLocationShare(context.env, {
        id: randomId(),
        userId: auth.userId,
        groupId,
        latitude,
        longitude,
        accuracyM,
        batteryLevel,
        source,
        ttlMs: ttlMinutes * 60_000,
        now,
      });

      await notifyGroup(context.env, groupId, {
        event: {
          kind: "LOCATION_SHARE",
          at: now,
          data: {
            userId: auth.userId,
            latitude,
            longitude,
            accuracyM: accuracyM ?? null,
            source,
            recordedAt: now,
            expiresAt: share.expires_at,
          },
        },
      });

      return dataResponse({ location: { userId: auth.userId, recordedAt: now, expiresAt: share.expires_at } }, 201);
    },
    { auth: true },
  );

  router.post(
    "/v1/me/checkins",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const body = asRecord(await readJsonBody(context.request));
      const durationMinutes = requireNumber(body.durationMinutes, "durationMinutes", { min: 1, max: 1440 });
      const note = optionalString(body.note, "note", { max: 500 }) ?? "";
      const groupId = optionalString(body.groupId, "groupId", { max: 64, trim: false });

      if (groupId) {
        const membership = await getMembership(context.env, groupId, auth.userId);
        if (!membership) throw forbidden("not_a_member", "You are not a member of this family group.");
      }

      const now = Date.now();
      const checkin = await insertCheckin(context.env, {
        id: randomId(),
        userId: auth.userId,
        groupId: groupId ?? null,
        durationMinutes,
        note,
        now,
      });

      if (checkin.group_id) {
        await notifyGroup(context.env, checkin.group_id, {
          event: {
            kind: "CHECKIN_UPDATED",
            at: now,
            data: {
              checkinId: checkin.id,
              userId: auth.userId,
              status: checkin.status,
              dueAt: checkin.due_at,
            },
          },
        });
      }

      return dataResponse(
        {
          checkin: {
            id: checkin.id,
            groupId: checkin.group_id,
            durationMinutes: checkin.duration_minutes,
            status: checkin.status,
            note: checkin.note,
            startedAt: checkin.started_at,
            dueAt: checkin.due_at,
          },
        },
        201,
      );
    },
    { auth: true },
  );

  router.get(
    "/v1/me/checkins",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const checkins = await listCheckins(context.env, auth.userId);
      return dataResponse({ checkins });
    },
    { auth: true },
  );

  router.patch(
    "/v1/me/checkins/:checkinId",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const checkinId = requireString(context.params.checkinId, "checkinId", { max: 64, trim: false });
      const body = asRecord(await readJsonBody(context.request));
      const status = requireEnum(body.status, "status", ["COMPLETED", "ALERTED"] as const);

      const updated = await completeCheckin(context.env, checkinId, auth.userId, status, Date.now());
      if (!updated) throw notFound("checkin_not_found", "Check-in not found.");

      if (updated.group_id) {
        await notifyGroup(context.env, updated.group_id, {
          event: {
            kind: "CHECKIN_UPDATED",
            at: Date.now(),
            data: { checkinId, userId: auth.userId, status },
          },
        });
      }

      return dataResponse({ checkin: { id: updated.id, status: updated.status, completedAt: updated.completed_at } });
    },
    { auth: true },
  );

  // Utility endpoint used by the client to discover which family group a
  // realtime scope maps to (avoids guessing ids on the device).
  router.get(
    "/v1/family/realtime-scopes",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const groups = await listGroupsForUser(context.env, auth.userId);
      return dataResponse({
        scopes: groups.map((group) => ({
          groupId: group.id,
          name: group.name,
          subscription: `/v1/realtime/family/${group.id}`,
        })),
      });
    },
    { auth: true },
  );
}
