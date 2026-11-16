const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function getDb(context) {
  return context.firestore();
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validUserProfile(uid) {
  const now = new Date();
  return {
    userId: uid,
    displayName: "Alice Pulse",
    about: "Available on PulseChat",
    phoneNumber: "+1 555 0100",
    countryCode: "+1",
    email: "alice@example.com",
    avatarColorHex: "#128C7E",
    themeMode: "system",
    notificationsEnabled: true,
    readReceiptsEnabled: true,
    lastSeenVisibility: "everyone",
    statusPrivacy: "everyone",
    appLanguage: "English",
    securityLockEnabled: false,
    isOnline: true,
    lastSeenText: "Online",
    createdAt: now,
    updatedAt: now,
  };
}

function validPublicProfile(uid) {
  const now = new Date();
  return {
    userId: uid,
    displayName: "Alice Pulse",
    about: "Available on PulseChat",
    avatarColorHex: "#128C7E",
    discoverable: true,
    isOnline: true,
    lastSeenText: "Online",
    createdAt: now,
    updatedAt: now,
  };
}

function validChat(ownerUid, participants) {
  const now = new Date();
  return {
    title: "Design Sync",
    isGroup: participants.length > 2,
    avatarColorHex: "#128C7E",
    participantIds: participants,
    createdBy: ownerUid,
    lastMessageText: "Hey there!",
    lastMessageType: "text",
    lastMessageSenderId: ownerUid,
    lastMessageStatus: "sent",
    pinnedBy: [],
    mutedBy: [],
    unreadCount: 0,
    createdAt: now,
    updatedAt: now,
  };
}

function validMessage(chatId, senderUid, participants) {
  const now = new Date();
  return {
    chatId,
    senderId: senderUid,
    senderName: "Alice Pulse",
    participantIds: participants,
    text: "Hello world!",
    mediaType: "text",
    mediaBase64: "",
    voiceDurationSec: 0,
    status: "sent",
    readBy: [senderUid],
    replyToText: "",
    createdAt: now,
    updatedAt: now,
  };
}

function validStatus(uid, privacy = "everyone", visibleTo = [uid, BOB_UID]) {
  const now = new Date();
  const expiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000);
  return {
    userId: uid,
    userName: "Alice Pulse",
    avatarColorHex: "#128C7E",
    mediaType: "text",
    caption: "Shipping PulseChat today!",
    mediaBase64: "",
    mediaUrl: "",
    storagePath: "",
    backgroundColorHex: "#075E54",
    fontStyle: "bold",
    privacy,
    visibleTo,
    viewedBy: [],
    expiresAt,
    createdAt: now,
    updatedAt: now,
  };
}

function validCommunity(ownerUid) {
  const now = new Date();
  return {
    name: "Android Builders",
    description: "Jetpack Compose & Material 3 fans",
    avatarColorHex: "#128C7E",
    ownerId: ownerUid,
    memberIds: [ownerUid, BOB_UID],
    linkedChatIds: [],
    announcementText: "Welcome to the community!",
    createdAt: now,
    updatedAt: now,
  };
}

function validCallSession(callerUid, receiverUid) {
  const now = new Date();
  return {
    callerId: callerUid,
    callerName: "Alice Pulse",
    receiverId: receiverUid,
    receiverName: "Bob",
    participantIds: [callerUid, receiverUid],
    callType: "video",
    direction: "outgoing",
    status: "calling",
    connectionState: "new",
    durationSec: 0,
    sdpOffer: "",
    sdpAnswer: "",
    createdAt: now,
    updatedAt: now,
  };
}

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = getDb(testEnv.unauthenticatedContext());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("chats").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("statuses").where("privacy", "==", "everyone").get());
  await assertFails(unauthDb.collection("communities").where("memberIds", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("call_sessions").where("participantIds", "array-contains", ALICE_UID).get());
});

test("User profile PII isolation: owner can create/read/update, other user rejected", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validUserProfile(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).update({
    about: "Building real-time apps",
    statusPrivacy: "contacts_except",
    statusExcludedIds: [CHARLIE_UID],
    statusAllowedIds: [BOB_UID],
    updatedAt: new Date(),
  }));

  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({
    isAdmin: true,
    updatedAt: new Date(),
  }));
});

test("Public profiles: signed-in users can query discoverable=true, only owner can write", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));

  await assertSucceeds(aliceDb.collection("public_profiles").doc(ALICE_UID).set(validPublicProfile(ALICE_UID)));
  await assertSucceeds(bobDb.collection("public_profiles").where("discoverable", "==", true).get());
  await assertFails(bobDb.collection("public_profiles").doc(ALICE_UID).update({
    displayName: "Hacked",
    updatedAt: new Date(),
  }));
});

test("Chats and messages: participants allowed, non-participants rejected", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  const charlieDb = getDb(testEnv.authenticatedContext(CHARLIE_UID));

  const chatId = "chat_alice_bob";
  const participants = [ALICE_UID, BOB_UID];

  await assertSucceeds(aliceDb.collection("chats").doc(chatId).set(validChat(ALICE_UID, participants)));
  await assertSucceeds(bobDb.collection("chats").where("participantIds", "array-contains", BOB_UID).get());
  await assertFails(charlieDb.collection("chats").doc(chatId).get());
  await assertFails(aliceDb.collection("chats").get());

  const msgId = "msg_1";
  await assertSucceeds(
    aliceDb.collection("chats").doc(chatId).collection("messages").doc(msgId)
      .set(validMessage(chatId, ALICE_UID, participants))
  );
  await assertSucceeds(
    bobDb.collection("chats").doc(chatId).collection("messages")
      .where("participantIds", "array-contains", BOB_UID).get()
  );
  await assertSucceeds(
    bobDb.collection("chats").doc(chatId).collection("messages").doc(msgId).update({
      status: "read",
      readBy: [ALICE_UID, BOB_UID],
      updatedAt: new Date(),
    })
  );
  await assertFails(charlieDb.collection("chats").doc(chatId).collection("messages").doc(msgId).get());
});

test("Statuses: privacy isolation, Storage metadata, and viewer updates", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  const charlieDb = getDb(testEnv.authenticatedContext(CHARLIE_UID));

  await assertSucceeds(aliceDb.collection("statuses").doc("st_public").set(validStatus(ALICE_UID, "everyone")));
  await assertSucceeds(aliceDb.collection("statuses").doc("st_only_bob").set(
    validStatus(ALICE_UID, "only_share_with", [ALICE_UID, BOB_UID])
  ));
  await assertSucceeds(aliceDb.collection("statuses").doc("st_private").set(
    validStatus(ALICE_UID, "private", [ALICE_UID])
  ));

  await assertSucceeds(bobDb.collection("statuses").where("privacy", "==", "everyone").get());
  await assertSucceeds(bobDb.collection("statuses").where("visibleTo", "array-contains", BOB_UID).get());
  await assertSucceeds(bobDb.collection("statuses").doc("st_only_bob").update({
    viewedBy: [BOB_UID],
    updatedAt: new Date(),
  }));
  await assertFails(charlieDb.collection("statuses").doc("st_only_bob").get());
  await assertFails(charlieDb.collection("statuses").doc("st_private").get());
  await assertFails(bobDb.collection("statuses").doc("st_public").delete());
});

test("Communities: member/owner enforcement", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  const charlieDb = getDb(testEnv.authenticatedContext(CHARLIE_UID));

  await assertSucceeds(aliceDb.collection("communities").doc("comm_1").set(validCommunity(ALICE_UID)));
  await assertSucceeds(bobDb.collection("communities").where("memberIds", "array-contains", BOB_UID).get());
  await assertFails(charlieDb.collection("communities").doc("comm_1").get());
});

test("Call sessions: WebRTC signaling, role-based state transitions, ICE candidates, and terminal lock", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  const charlieDb = getDb(testEnv.authenticatedContext(CHARLIE_UID));

  // Self-calls and spoofed callerId rejected
  await assertFails(aliceDb.collection("call_sessions").doc("bad_self").set(validCallSession(ALICE_UID, ALICE_UID)));
  await assertFails(bobDb.collection("call_sessions").doc("bad_spoof").set(validCallSession(ALICE_UID, BOB_UID)));

  await assertSucceeds(aliceDb.collection("call_sessions").doc("call_1").set(validCallSession(ALICE_UID, BOB_UID)));

  // Caller sets SDP offer
  await assertSucceeds(aliceDb.collection("call_sessions").doc("call_1").update({
    sdpOffer: "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n",
    updatedAt: new Date(),
  }));

  // Receiver cannot cancel call (caller-only action), and caller cannot reject
  await assertFails(bobDb.collection("call_sessions").doc("call_1").update({
    status: "cancelled",
    connectionState: "closed",
    endedAt: new Date(),
    updatedAt: new Date(),
  }));
  await assertFails(aliceDb.collection("call_sessions").doc("call_1").update({
    status: "rejected",
    connectionState: "closed",
    endedAt: new Date(),
    updatedAt: new Date(),
  }));

  // Receiver marks ringing, accepts, sends SDP answer, and transitions to connected
  await assertSucceeds(bobDb.collection("call_sessions").doc("call_1").update({
    status: "ringing",
    updatedAt: new Date(),
  }));
  await assertSucceeds(bobDb.collection("call_sessions").doc("call_1").update({
    status: "accepted",
    startedAt: new Date(),
    updatedAt: new Date(),
  }));
  await assertSucceeds(bobDb.collection("call_sessions").doc("call_1").update({
    sdpAnswer: "v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\n",
    status: "accepted",
    connectionState: "checking",
    updatedAt: new Date(),
  }));
  await assertSucceeds(aliceDb.collection("call_sessions").doc("call_1").update({
    status: "connected",
    connectionState: "connected",
    updatedAt: new Date(),
  }));

  // Participants exchange ICE candidates; non-participant Charlie is denied
  const candPayload = (uid) => ({
    callId: "call_1",
    senderId: uid,
    participantIds: [ALICE_UID, BOB_UID],
    sdpMid: "0",
    sdpMLineIndex: 0,
    candidate: "candidate:1 1 UDP 2122252543 192.0.2.1 54321 typ host",
    createdAt: new Date(),
  });
  await assertSucceeds(aliceDb.collection("call_sessions").doc("call_1").collection("candidates").doc("c1").set(candPayload(ALICE_UID)));
  await assertSucceeds(bobDb.collection("call_sessions").doc("call_1").collection("candidates").where("participantIds", "array-contains", BOB_UID).get());
  await assertFails(charlieDb.collection("call_sessions").doc("call_1").collection("candidates").doc("c2").set(candPayload(CHARLIE_UID)));
  await assertFails(charlieDb.collection("call_sessions").doc("call_1").collection("candidates").doc("c1").get());
  await assertFails(charlieDb.collection("call_sessions").doc("call_1").collection("candidates").get());
  await assertFails(aliceDb.collection("call_sessions").doc("call_1").collection("candidates").doc("c1").update({ candidate: "tampered" }));

  // Unauthorized Charlie cannot read or terminate the call
  await assertFails(charlieDb.collection("call_sessions").doc("call_1").get());
  await assertFails(charlieDb.collection("call_sessions").doc("call_1").update({
    status: "ended",
    connectionState: "closed",
    durationSec: 1,
    endedAt: new Date(),
    updatedAt: new Date(),
  }));

  // Participant ends call with endedAt & durationSec
  await assertSucceeds(bobDb.collection("call_sessions").doc("call_1").update({
    status: "ended",
    connectionState: "closed",
    durationSec: 42,
    endedAt: new Date(),
    updatedAt: new Date(),
  }));

  // Terminal state lock: once ended, cannot mutate or add ICE candidates
  await assertFails(aliceDb.collection("call_sessions").doc("call_1").update({
    status: "connected",
    updatedAt: new Date(),
  }));
  await assertFails(aliceDb.collection("call_sessions").doc("call_1").collection("candidates").doc("c3").set(candPayload(ALICE_UID)));
});

test("Call sessions: caller can cancel ringing call before answer", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));

  await assertSucceeds(aliceDb.collection("call_sessions").doc("call_cancel").set(validCallSession(ALICE_UID, BOB_UID)));
  await assertSucceeds(aliceDb.collection("call_sessions").doc("call_cancel").update({
    status: "cancelled",
    connectionState: "closed",
    durationSec: 0,
    endedAt: new Date(),
    updatedAt: new Date(),
  }));
  // Receiver cannot accept after caller cancelled
  await assertFails(bobDb.collection("call_sessions").doc("call_cancel").update({
    status: "accepted",
    updatedAt: new Date(),
  }));
});

test("User profile supports saving owner FCM token", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validUserProfile(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).update({
    fcmToken: "fcm_test_token_12345",
    updatedAt: new Date(),
  }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({
    fcmToken: "hijacked_token",
    updatedAt: new Date(),
  }));
});

test("Phone directory: exact get allowed, list scraping denied, cross-user overwrite denied", async () => {
  const unauthDb = getDb(testEnv.unauthenticatedContext());
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  const hashId = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3";
  const now = new Date();

  await assertSucceeds(aliceDb.collection("phone_directory").doc(hashId).set({
    userId: ALICE_UID,
    displayName: "Alice Pulse",
    avatarColorHex: "#128C7E",
    createdAt: now,
    updatedAt: now,
  }));

  await assertSucceeds(bobDb.collection("phone_directory").doc(hashId).get());
  await assertFails(bobDb.collection("phone_directory").get());
  await assertFails(unauthDb.collection("phone_directory").doc(hashId).get());
  await assertFails(bobDb.collection("phone_directory").doc(hashId).update({
    displayName: "Hijacked",
    updatedAt: new Date(),
  }));
});
