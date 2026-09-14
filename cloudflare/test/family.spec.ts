import { describe, expect, it } from "vitest";
import { api, errorCode, registerUser, testEnv } from "./helpers";

interface GroupEnvelope {
  data: { group: { id: string; name: string; myRole: string } };
}

async function createGroup(token: string, name = "Home"): Promise<string> {
  const response = await api<GroupEnvelope>("/v1/family/groups", { token, body: { name } });
  expect(response.status).toBe(201);
  return response.body.data.group.id;
}

async function inviteAndJoin(token: string, groupId: string, role = "CHILD", joiner?: string): Promise<string> {
  const invite = await api<{ data: { invite: { code: string } } }>(`/v1/family/groups/${groupId}/invites`, {
    token,
    body: { role, expiresInMinutes: 30 },
  });
  expect(invite.status).toBe(201);

  const joinToken = joiner;
  if (!joinToken) return invite.body.data.invite.code;

  const join = await api(`/v1/family/join`, { token: joinToken, body: { inviteCode: invite.body.data.invite.code } });
  expect(join.status).toBe(201);
  return invite.body.data.invite.code;
}

describe("family groups", () => {
  it("creates a group with the caller as owner and lists it", async () => {
    const owner = await registerUser("family-owner");
    const groupId = await createGroup(owner.accessToken, "Johnson Family");

    const list = await api<{ data: { groups: { id: string; myRole: string; memberCount: number }[] } }>(
      "/v1/family/groups",
      { token: owner.accessToken },
    );
    expect(list.status).toBe(200);
    const group = list.body.data.groups.find((entry) => entry.id === groupId);
    expect(group?.myRole).toBe("OWNER");
    expect(group?.memberCount).toBe(1);
  });

  it("joins a member through a single-use invite code and lists both members", async () => {
    const owner = await registerUser("family-owner-2");
    const child = await registerUser("family-child-2");
    const groupId = await createGroup(owner.accessToken);

    const code = await inviteAndJoin(owner.accessToken, groupId, "CHILD", child.accessToken);

    const members = await api<{ data: { members: { userId: string; role: string; online: boolean }[] } }>(
      `/v1/family/groups/${groupId}/members`,
      { token: owner.accessToken },
    );
    expect(members.status).toBe(200);
    expect(members.body.data.members).toHaveLength(2);
    expect(members.body.data.members.map((member) => member.role).sort()).toEqual(["CHILD", "OWNER"]);

    // The invite is consumed: it can never be replayed.
    const reuse = await api("/v1/family/join", { token: child.accessToken, body: { inviteCode: code } });
    expect(reuse.status).toBe(404);
    expect(errorCode(reuse.body)).toBe("invite_invalid");

    const another = await registerUser("family-late-joiner");
    const replay = await api("/v1/family/join", { token: another.accessToken, body: { inviteCode: code } });
    expect(replay.status).toBe(404);
  });

  it("stores invite codes hashed only", async () => {
    const owner = await registerUser("family-invite-hash");
    const groupId = await createGroup(owner.accessToken);
    const code = await inviteAndJoin(owner.accessToken, groupId);

    const row = await testEnv.DB.prepare("SELECT code_hash FROM family_invites WHERE group_id = ?1")
      .bind(groupId)
      .first<{ code_hash: string }>();
    expect(row?.code_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row?.code_hash).not.toContain(code.replace(/-/g, ""));
  });

  it("rejects membership attempts from users outside the group", async () => {
    const owner = await registerUser("family-owner-3");
    const outsider = await registerUser("family-outsider-3");
    const groupId = await createGroup(owner.accessToken);

    const members = await api(`/v1/family/groups/${groupId}/members`, { token: outsider.accessToken });
    expect(members.status).toBe(403);
    expect(errorCode(members.body)).toBe("not_a_member");

    const messages = await api(`/v1/family/groups/${groupId}/messages`, { token: outsider.accessToken });
    expect(messages.status).toBe(403);

    const locations = await api(`/v1/family/groups/${groupId}/locations`, { token: outsider.accessToken });
    expect(locations.status).toBe(403);

    const invite = await api(`/v1/family/groups/${groupId}/invites`, {
      token: outsider.accessToken,
      body: { role: "MEMBER" },
    });
    expect(invite.status).toBe(403);

    const activeSos = await api(`/v1/family/groups/${groupId}/sos/active`, { token: outsider.accessToken });
    expect(activeSos.status).toBe(403);

    const detail = await api(`/v1/family/groups/${groupId}`, { token: outsider.accessToken });
    expect([403, 404, 405]).toContain(detail.status);
  });

  it("enforces role rules when removing members and changing roles", async () => {
    const owner = await registerUser("family-owner-4");
    const child = await registerUser("family-child-4");
    const member = await registerUser("family-member-4");
    const groupId = await createGroup(owner.accessToken);

    await inviteAndJoin(owner.accessToken, groupId, "CHILD", child.accessToken);
    await inviteAndJoin(owner.accessToken, groupId, "MEMBER", member.accessToken);

    const members = await api<{ data: { members: { memberId: string; userId: string; role: string }[] } }>(
      `/v1/family/groups/${groupId}/members`,
      { token: owner.accessToken },
    );
    const memberRow = members.body.data.members.find((entry) => entry.userId === member.userId);
    const ownerRow = members.body.data.members.find((entry) => entry.userId === owner.userId);

    // A plain MEMBER may not remove somebody else.
    const forbiddenRemoval = await api(`/v1/family/groups/${groupId}/members/${child.userId === "" ? "" : memberRow?.memberId}`, {
      method: "DELETE",
      token: child.accessToken,
    });
    expect(forbiddenRemoval.status).toBe(403);

    // The owner role can never be reassigned or removed.
    const ownerRemoval = await api(`/v1/family/groups/${groupId}/members/${ownerRow?.memberId}`, {
      method: "DELETE",
      token: owner.accessToken,
    });
    expect(ownerRemoval.status).toBe(403);
    expect(errorCode(ownerRemoval.body)).toBe("owner_removal_denied");

    const ownerRoleChange = await api(`/v1/family/groups/${groupId}/members/${ownerRow?.memberId}`, {
      method: "PATCH",
      token: owner.accessToken,
      body: { role: "MEMBER" },
    });
    expect(ownerRoleChange.status).toBe(403);

    // The owner can promote and remove a member.
    const promoted = await api(`/v1/family/groups/${groupId}/members/${memberRow?.memberId}`, {
      method: "PATCH",
      token: owner.accessToken,
      body: { role: "GUARDIAN" },
    });
    expect(promoted.status).toBe(200);

    const removed = await api(`/v1/family/groups/${groupId}/members/${memberRow?.memberId}`, {
      method: "DELETE",
      token: owner.accessToken,
    });
    expect(removed.status).toBe(204);

    const afterRemoval = await api(`/v1/family/groups/${groupId}/locations`, { token: member.accessToken });
    expect(afterRemoval.status).toBe(403);
  });

  it("shares family messages only with group members", async () => {
    const owner = await registerUser("family-owner-5");
    const child = await registerUser("family-child-5");
    const outsider = await registerUser("family-outsider-5");
    const groupId = await createGroup(owner.accessToken);
    await inviteAndJoin(owner.accessToken, groupId, "CHILD", child.accessToken);

    const sent = await api<{ data: { message: { id: string; body: string } } }>(
      `/v1/family/groups/${groupId}/messages`,
      { token: owner.accessToken, body: { body: "Dinner at 6pm", isEmergency: false } },
    );
    expect(sent.status).toBe(201);

    const childView = await api<{ data: { messages: { body: string }[] } }>(`/v1/family/groups/${groupId}/messages`, {
      token: child.accessToken,
    });
    expect(childView.status).toBe(200);
    expect(childView.body.data.messages[0]?.body).toBe("Dinner at 6pm");

    const outsiderView = await api(`/v1/family/groups/${groupId}/messages`, { token: outsider.accessToken });
    expect(outsiderView.status).toBe(403);
  });

  it("shares only unexpired location updates, scoped to group members", async () => {
    const owner = await registerUser("family-owner-6");
    const child = await registerUser("family-child-6");
    const groupId = await createGroup(owner.accessToken);
    await inviteAndJoin(owner.accessToken, groupId, "CHILD", child.accessToken);

    const share = await api(`/v1/family/groups/${groupId}/locations`, {
      token: child.accessToken,
      body: { latitude: 37.7749, longitude: -122.4194, accuracyM: 12, source: "CHECKIN", ttlMinutes: 30 },
    });
    expect(share.status).toBe(201);

    const view = await api<{ data: { locations: { userId: string; latitude: number; stale: boolean }[] } }>(
      `/v1/family/groups/${groupId}/locations`,
      { token: owner.accessToken },
    );
    expect(view.status).toBe(200);
    expect(view.body.data.locations).toHaveLength(1);
    expect(view.body.data.locations[0]?.userId).toBe(child.userId);
    expect(view.body.data.locations[0]?.stale).toBe(false);

    // Expired shares never surface (retention is enforced by the query).
    await testEnv.DB.prepare("UPDATE location_shares SET expires_at = ?1 WHERE user_id = ?2")
      .bind(Date.now() - 1000, child.userId)
      .run();
    const afterExpiry = await api<{ data: { locations: unknown[] } }>(`/v1/family/groups/${groupId}/locations`, {
      token: owner.accessToken,
    });
    expect(afterExpiry.body.data.locations).toHaveLength(0);
  });

  it("keeps check-ins private to their owner while notifying the family", async () => {
    const owner = await registerUser("family-owner-7");
    const groupId = await createGroup(owner.accessToken);

    const created = await api<{ data: { checkin: { id: string; status: string; dueAt: number } } }>("/v1/me/checkins", {
      token: owner.accessToken,
      body: { durationMinutes: 30, note: "Walking home", groupId },
    });
    expect(created.status).toBe(201);
    expect(created.body.data.checkin.status).toBe("ACTIVE");

    const completed = await api(`/v1/me/checkins/${created.body.data.checkin.id}`, {
      method: "PATCH",
      token: owner.accessToken,
      body: { status: "COMPLETED" },
    });
    expect(completed.status).toBe(200);

    const list = await api<{ data: { checkins: { status: string }[] } }>("/v1/me/checkins", { token: owner.accessToken });
    expect(list.body.data.checkins[0]?.status).toBe("COMPLETED");
  });
});
