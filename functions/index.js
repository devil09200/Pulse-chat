const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const logger = require("firebase-functions/logger");

initializeApp();

const DATABASE_ID = "ai-studio-android-pulsecha-85ec5974-81cf-42a5-851c-3b2669d966e1";

/**
 * Triggered whenever a new WebRTC call session is created in Firestore.
 * Looks up the receiver's FCM registration token in `users/{receiverId}`
 * and dispatches a high-priority data-only FCM HTTP v1 message.
 */
exports.notifyIncomingCall = onDocumentCreated(
  {
    document: "call_sessions/{callId}",
    database: DATABASE_ID,
  },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
      return null;
    }

    const callId = event.params.callId;
    const callData = snapshot.data() || {};

    const callerId = String(callData.callerId || "").trim();
    const receiverId = String(callData.receiverId || callData.calleeId || "").trim();
    const status = String(callData.status || "").trim();

    if (!callerId || !receiverId || callerId === receiverId) {
      logger.info("Skipping FCM trigger: missing or identical participants", { callId });
      return null;
    }

    if (status !== "calling" && status !== "ringing") {
      logger.info("Skipping FCM trigger: call is not in ringing/calling state", { callId, status });
      return null;
    }

    const db = getFirestore(DATABASE_ID);
    const receiverRef = db.collection("users").doc(receiverId);
    const receiverSnap = await receiverRef.get();

    if (!receiverSnap.exists) {
      logger.warn("Receiver profile not found for incoming call notification", { callId, receiverId });
      return null;
    }

    const receiverData = receiverSnap.data() || {};
    if (receiverData.notificationsEnabled === false) {
      logger.info("Receiver disabled notifications", { callId, receiverId });
      return null;
    }

    const fcmToken = String(receiverData.fcmToken || "").trim();
    if (!fcmToken) {
      logger.info("No FCM token registered for receiver", { callId, receiverId });
      return null;
    }

    const callerName = String(callData.callerName || "PulseChat Contact").trim().slice(0, 80);
    const callerColor = String(callData.callerAvatarColorHex || "#128C7E").trim().slice(0, 16);
    const callType = callData.callType === "video" ? "video" : "voice";

    // Respect FCM HTTP v1 4096-byte payload cap: include avatar only if compact URL/thumbnail.
    const rawAvatar = String(callData.callerPhotoBase64 || "").trim();
    const callerAvatar = rawAvatar.length <= 1800 ? rawAvatar : "";

    const message = {
      token: fcmToken,
      data: {
        type: "incoming_call",
        callId: String(callId),
        callerId,
        callerName,
        callerAvatar,
        callerColor,
        callType,
      },
      android: {
        priority: "high",
        ttl: 45 * 1000,
      },
    };

    try {
      const responseId = await getMessaging().send(message);
      logger.info("Sent incoming call FCM HTTP v1 notification", { callId, receiverId, responseId });
      return responseId;
    } catch (error) {
      const errorCode = error && error.code ? String(error.code) : "";
      logger.error("Failed to send incoming call FCM notification", { callId, receiverId, errorCode, error });

      if (
        errorCode === "messaging/registration-token-not-registered" ||
        errorCode === "messaging/invalid-registration-token"
      ) {
        await receiverRef.update({
          fcmToken: "",
          updatedAt: FieldValue.serverTimestamp(),
        }).catch(() => null);
      }
      return null;
    }
  }
);
