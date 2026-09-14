/**
 * Profile and emergency-contact endpoints.
 *
 * Medical data (blood group, allergies, medication, insurance) is deliberately
 * NOT synchronised: it lives only in the encrypted on-device database, which is
 * the minimum data necessary for the emergency screens to work. The cloud
 * profile contains identity and contact data only.
 */

import { randomId } from "../util/crypto";
import { conflict, notFound, unauthorized } from "../util/errors";
import { asRecord, optionalBoolean, optionalNumber, optionalString, requirePhone, requireString } from "../util/validate";
import {
  countContacts,
  deleteContact,
  getContact,
  insertContact,
  listContacts,
  toPublicContact,
  updateContact,
} from "../db/contacts";
import { findUserById, toPublicUser, updateProfile } from "../db/users";
import { upsertPresence } from "../db/presence";
import { updatePresenceInScopes } from "../safety/broadcast";
import { enforceRateLimit, RATE_LIMITS } from "../util/ratelimit";
import { dataResponse, emptyResponse, readJsonBody, type Router } from "../http";

export function registerProfileRoutes(router: Router): void {
  router.get(
    "/v1/me/profile",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const user = await findUserById(context.env, auth.userId);
      if (!user) throw notFound("user_not_found", "Account not found.");
      return dataResponse({ user: toPublicUser(user) });
    },
    { auth: true },
  );

  router.put(
    "/v1/me/profile",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const body = asRecord(await readJsonBody(context.request));
      const displayName = optionalString(body.displayName, "displayName", { min: 1, max: 80 });
      const locale = optionalString(body.locale, "locale", { max: 16 });
      const phone = body.phone === null ? null : body.phone === undefined ? undefined : requirePhone(body.phone);

      const user = await updateProfile(
        context.env,
        auth.userId,
        { displayName, phone, locale },
        Date.now(),
      );
      if (!user) throw notFound("user_not_found", "Account not found.");
      return dataResponse({ user: toPublicUser(user) });
    },
    { auth: true },
  );

  router.get(
    "/v1/me/emergency-contacts",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const contacts = await listContacts(context.env, auth.userId);
      return dataResponse({ contacts: contacts.map(toPublicContact) });
    },
    { auth: true },
  );

  router.post(
    "/v1/me/emergency-contacts",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const body = asRecord(await readJsonBody(context.request));
      const name = requireString(body.name, "name", { min: 1, max: 80 });
      const phone = requirePhone(body.phone);
      const relationship = optionalString(body.relationship, "relationship", { max: 40 }) ?? "";
      const isVerified = optionalBoolean(body.isVerified, "isVerified") ?? false;
      const priority = optionalNumber(body.priority, "priority", { min: 0, max: 100 }) ?? 0;

      const existing = await countContacts(context.env, auth.userId);
      if (existing >= 20) {
        throw conflict("contact_limit_reached", "A maximum of 20 emergency contacts is supported.");
      }

      const contact = await insertContact(context.env, {
        id: randomId(),
        userId: auth.userId,
        name,
        phone,
        relationship,
        isVerified,
        priority,
        now: Date.now(),
      });
      if (!contact) throw notFound("contact_create_failed", "Contact could not be created.");
      return dataResponse({ contact: toPublicContact(contact) }, 201);
    },
    { auth: true },
  );

  router.patch(
    "/v1/me/emergency-contacts/:contactId",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const contactId = requireString(context.params.contactId, "contactId", { max: 64, trim: false });
      const body = asRecord(await readJsonBody(context.request));

      const owned = await getContact(context.env, auth.userId, contactId);
      if (!owned) throw notFound("contact_not_found", "Contact not found.");

      const contact = await updateContact(context.env, {
        userId: auth.userId,
        contactId,
        name: optionalString(body.name, "name", { min: 1, max: 80 }),
        phone: body.phone === undefined ? undefined : requirePhone(body.phone),
        relationship: optionalString(body.relationship, "relationship", { max: 40 }),
        isVerified: optionalBoolean(body.isVerified, "isVerified"),
        priority: optionalNumber(body.priority, "priority", { min: 0, max: 100 }),
        now: Date.now(),
      });
      if (!contact) throw notFound("contact_not_found", "Contact not found.");
      return dataResponse({ contact: toPublicContact(contact) });
    },
    { auth: true },
  );

  router.delete(
    "/v1/me/emergency-contacts/:contactId",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const contactId = requireString(context.params.contactId, "contactId", { max: 64, trim: false });
      const removed = await deleteContact(context.env, auth.userId, contactId);
      if (!removed) throw notFound("contact_not_found", "Contact not found.");
      return emptyResponse(204);
    },
    { auth: true },
  );

  // Presence heartbeat: keeps family "online" state accurate without holding a
  // WebSocket open (used when the app is backgrounded or the socket drops).
  router.post(
    "/v1/me/presence",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const body = asRecord(await readJsonBody(context.request));
      const status = requireString(body.status, "status", { max: 8 });
      if (status !== "ONLINE" && status !== "OFFLINE") {
        throw notFound("invalid_status", "Status must be ONLINE or OFFLINE.");
      }
      const batteryLevel = optionalNumber(body.batteryLevel, "batteryLevel", { min: 0, max: 100 });

      await enforceRateLimit(context.env.DB, `presence:${auth.userId}`, RATE_LIMITS.readPerUser);

      const now = Date.now();
      await upsertPresence(context.env, { userId: auth.userId, status, batteryLevel, source: "APP", now });
      await updatePresenceInScopes(context.env, auth.userId, status, batteryLevel ?? null, now);

      return dataResponse({ status, recordedAt: now });
    },
    { auth: true },
  );
}
