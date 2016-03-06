/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc;

import android.util.Log;

import org.appspot.apprtc.util.LooperExecutor;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.List;

import fm.Guid;
import fm.SingleAction;
import fm.websync.Client;
import fm.websync.ConnectArgs;
import fm.websync.ConnectFailureArgs;
import fm.websync.ConnectSuccessArgs;
import fm.websync.DisconnectArgs;
import fm.websync.DisconnectCompleteArgs;
import fm.websync.NotifyArgs;
import fm.websync.NotifyReceiveArgs;
import fm.websync.SubscribeArgs;
import fm.websync.SubscribeFailureArgs;
import fm.websync.SubscribeReceiveArgs;
import fm.websync.SubscribeSuccessArgs;
import fm.websync.subscribers.ClientSubscribeArgs;
import fm.websync.subscribers.SubscribeArgsExtensions;

/**
 * Negotiates signaling for chatting with apprtc.appspot.com "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 *
 * <p>To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
public class WebSyncRTCClient implements AppRTCClient {
  private static final String TAG = "ILRTCClient";
  private static final String ROOM_JOIN = "join";
  private static final String ROOM_MESSAGE = "message";
  private static final String ROOM_LEAVE = "leave";

  private enum ConnectionState {
    NEW, CONNECTED, CLOSED, ERROR
  };

  private final LooperExecutor executor;
  private boolean initiator;
  private SignalingEvents events;
  private Client wsClient;
  private Guid guid;
  private ConnectionState roomState;
  private RoomConnectionParameters connectionParameters;
  private String messageUrl;
  private String leaveUrl;
  private String peerId;

  public WebSyncRTCClient(SignalingEvents events, LooperExecutor executor) {
    this.events = events;
    this.executor = executor;
    roomState = ConnectionState.NEW;
    executor.requestStart();
  }

  @Override
  public void connectToRoom(RoomConnectionParameters connectionParameters) {
    this.connectionParameters = connectionParameters;
    executor.execute(new Runnable() {
      @Override
      public void run() {
        connectToRoomInternal();
      }
    });
  }

  @Override
  public void disconnectFromRoom() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        disconnectFromRoomInternal();
      }
    });
    executor.requestStop();
  }

  private void createSignalParameters(boolean i) {
    List<PeerConnection.IceServer> iceServers = new ArrayList<PeerConnection.IceServer>();
    iceServers.add(new PeerConnection.IceServer("turn:demo.icelink.fm:3478", "test", "pa55w0rd!"));
    SignalingParameters params = new SignalingParameters(
            iceServers,
            i,
            guid.toString(),
            getConnectionUrl(connectionParameters),
            getConnectionUrl(connectionParameters),
            null, null);

    signalingParametersReady(params);

  }
  // Connects to room - function runs on a local looper thread.
  private void connectToRoomInternal() {
    String connectionUrl = getConnectionUrl(connectionParameters);
    String roomId = getRoomId(connectionParameters);
    Log.d(TAG, "Connect to room: " + connectionUrl + "/" + roomId);
    roomState = ConnectionState.NEW;

    try {
      wsClient = new Client(connectionUrl);

      // Create a persistent connection to the server.
      wsClient.connect(new ConnectArgs() {{
        setOnFailure(new SingleAction<ConnectFailureArgs>() {
          public void invoke(ConnectFailureArgs e) {
            fm.Log.error("Could not connect to WebSync! " + e.getException().getMessage());
            e.setRetry(false);
          }
        });
        setOnSuccess(new SingleAction<ConnectSuccessArgs>() {
          public void invoke(ConnectSuccessArgs e) {
            fm.Log.info("Connect to WebSync! " + e.toString());
            guid = e.getClient().getClientId();

          }
        });
      }});

      wsClient.addOnNotify(new SingleAction<NotifyReceiveArgs>() {
        public void invoke(NotifyReceiveArgs e) {
          fm.Log.info(e.toString());

          try {
            peerId = e.getNotifyingClient().getClientId().toString();
            Log.e(TAG, e.getDataJson().toString());

            JSONObject json = new JSONObject(e.getDataJson());
//            String sdpMessage = null;
//            boolean isOffer = false;
//            if (!json.isNull("sdpMessage")) {
//              sdpMessage = json.getString("sdpMessage");
//              isOffer = json.getBoolean("isOffer");
//            }
            String type = json.getString("type");
//            peerId = json.getString("peerid");
//            Log.e(TAG, "PeerID: " + peerId + "/nJSON: " + json);

            if (type.equals("candidate")) {
              IceCandidate candidate = new IceCandidate(
                      json.getString("id"),
                      json.getInt("label"),
                      json.getString("candidate"));
              events.onRemoteIceCandidate(candidate);
            } else if (type.equals("answer")) {
              if (initiator) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        json.getString("sdp"));
                events.onRemoteDescription(sdp);
              }
            } else if (type.equals("offer")) {
              createSignalParameters(false);

              if (!initiator) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        json.getString("sdp"));
                events.onRemoteDescription(sdp);
              }
            } else if (type.equals("bye")) {
              events.onChannelClose();
            }
          } catch (Exception je) {
            je.printStackTrace();
          }
        }
      });

      // Subscribe to a WebSync channel. When another client joins the same
      // channel, create a P2P link. When a client leaves, destroy it.
      SubscribeArgs subscribeArgs = new SubscribeArgs("/" + roomId) {{
          setOnFailure(new SingleAction<SubscribeFailureArgs>() {
            public void invoke(SubscribeFailureArgs e) {
              fm.Log.error("Error WebSync channel! " + e.getException().getMessage());
            }
          });
          setOnSuccess(new SingleAction<SubscribeSuccessArgs>() {
            public void invoke(SubscribeSuccessArgs e) {
              fm.Log.info(e.toString());

            }
          });
          setOnReceive(new SingleAction<SubscribeReceiveArgs>() {
            public void invoke(SubscribeReceiveArgs e) {
              if (e != null) {
                String s = e.getDataJson();

                fm.Log.info(s);
              }
            }
          });
        }};
      SubscribeArgsExtensions.setOnClientSubscribe(subscribeArgs, new SingleAction<ClientSubscribeArgs>() {
        public void invoke(ClientSubscribeArgs e) {
          try {
            // Kick off a P2P link.
            peerId = e.getSubscribedClient().getClientId().toString();

            createSignalParameters(true);

            Log.e(TAG, "Connected new client: " + peerId);
          } catch (Exception ex) {
            ex.printStackTrace();
          }
        }
      });
      wsClient.subscribe(subscribeArgs);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // Disconnect from room and send bye messages - runs on a local looper thread.
  private void disconnectFromRoomInternal() {
    Log.d(TAG, "Disconnect. Room state: " + roomState);
    if (roomState == ConnectionState.CONNECTED) {
      Log.d(TAG, "Closing room.");
      JSONObject json = new JSONObject();
      jsonPut(json, "type", "bye");
      send(json.toString());
    }
    roomState = ConnectionState.CLOSED;
    if (wsClient != null) {
      // Tear down the persistent connection.
      try {
        wsClient.disconnect(new DisconnectArgs() {{
          setOnComplete(new SingleAction<DisconnectCompleteArgs>() {
            public void invoke(DisconnectCompleteArgs e) {
              fm.Log.error(e.toString());
            }
          });
        }});
      } catch (Exception e) {
        e.printStackTrace();
      }

      wsClient = null;
    }
  }

  // Helper functions to get connection, post message and leave message URLs
  private String getConnectionUrl(RoomConnectionParameters connectionParameters) {
    return connectionParameters.roomUrl;
  }

  private String getRoomId(RoomConnectionParameters connectionParameters) {
    return connectionParameters.roomId;
  }

  private String getMessageUrl(RoomConnectionParameters connectionParameters,
      SignalingParameters signalingParameters) {
    return connectionParameters.roomUrl + "/" + ROOM_MESSAGE + "/"
      + connectionParameters.roomId + "/" + signalingParameters.clientId;
  }

  private String getLeaveUrl(RoomConnectionParameters connectionParameters,
      SignalingParameters signalingParameters) {
    return connectionParameters.roomUrl + "/" + ROOM_LEAVE + "/"
        + connectionParameters.roomId + "/" + signalingParameters.clientId;
  }

  // Callback issued when room parameters are extracted. Runs on local
  // looper thread.
  private void signalingParametersReady(final SignalingParameters signalingParameters) {
    Log.d(TAG, "Room connection completed.");
    if (connectionParameters.loopback
        && (!signalingParameters.initiator
            || signalingParameters.offerSdp != null)) {
      reportError("Loopback room is busy.");
      return;
    }
    if (!connectionParameters.loopback
        && !signalingParameters.initiator
        && signalingParameters.offerSdp == null) {
      Log.w(TAG, "No offer SDP in room response.");
    }
    initiator = signalingParameters.initiator;
    messageUrl = getMessageUrl(connectionParameters, signalingParameters);
    leaveUrl = getLeaveUrl(connectionParameters, signalingParameters);
    Log.d(TAG, "Message URL: " + messageUrl);
    Log.d(TAG, "Leave URL: " + leaveUrl);
    roomState = ConnectionState.CONNECTED;

    // Fire connection and signaling parameters events.
    events.onConnectedToRoom(signalingParameters);
  }

  // Send local offer SDP to the other participant.
  @Override
  public void sendOfferSdp(final SessionDescription sdp) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (roomState != ConnectionState.CONNECTED) {
          reportError("Sending offer SDP in non connected state.");
          return;
        }
        JSONObject json = new JSONObject();
        jsonPut(json, "sdp", sdp.description);
        jsonPut(json, "type", "offer");
//        jsonPut(json, "sdpMessage", sdp.description);
//        jsonPut(json, "isOffer", true);
        send(json.toString());
        if (connectionParameters.loopback) {
          // In loopback mode rename this offer to answer and route it back.
          SessionDescription sdpAnswer = new SessionDescription(
              SessionDescription.Type.fromCanonicalForm("answer"),
              sdp.description);
          events.onRemoteDescription(sdpAnswer);
        }
      }
    });
  }

  // Send local answer SDP to the other participant.
  @Override
  public void sendAnswerSdp(final SessionDescription sdp) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (connectionParameters.loopback) {
          Log.e(TAG, "Sending answer in loopback mode.");
          return;
        }
        JSONObject json = new JSONObject();
        jsonPut(json, "sdp", sdp.description);
        jsonPut(json, "type", "answer");
//        jsonPut(json, "sdpMessage", sdp.description);
//        jsonPut(json, "isOffer", "false");
        send(json.toString());
      }
    });
  }

  // Send Ice candidate to the other participant.
  @Override
  public void sendLocalIceCandidate(final IceCandidate candidate) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        JSONObject json = new JSONObject();
        jsonPut(json, "type", "candidate");
        jsonPut(json, "label", candidate.sdpMLineIndex);
        jsonPut(json, "id", candidate.sdpMid);
        jsonPut(json, "candidate", candidate.sdp);
        Log.e(TAG, candidate.toString());
        if (initiator) {
          // Call initiator sends ice candidates to GAE server.
          if (roomState != ConnectionState.CONNECTED) {
            reportError("Sending ICE candidate in non connected state.");
            return;
          }
          send(json.toString());
          if (connectionParameters.loopback) {
            events.onRemoteIceCandidate(candidate);
          }
        } else {
           // Call receiver sends ice candidates to websocket server.
          send(json.toString());
        }
      }
    });
  }

  private void reportError(final String errorMessage) {
    Log.e(TAG, errorMessage);
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (roomState != ConnectionState.ERROR) {
          roomState = ConnectionState.ERROR;
          events.onChannelError(errorMessage);
        }
      }
    });
  }

  // Put a |key|->|value| mapping in |json|.
  private static void jsonPut(JSONObject json, String key, Object value) {
    try {
      json.put(key, value);
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }

  private void send(String message) {
    if (wsClient == null) {
      fm.Log.error("Error send message! Please check WebSync connection!");
      return;
    }

    try {
      wsClient.notify(new NotifyArgs(new Guid(peerId), message, getRoomId(connectionParameters)));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}