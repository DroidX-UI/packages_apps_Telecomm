/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom;

import static java.util.Map.entry;

import android.content.Context;
import android.os.SystemProperties;

import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.Logging.EventManager;
import android.telecom.ParcelableCallAnalytics;
import android.telecom.TelecomAnalytics;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Base64;
import android.telecom.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.nano.TelecomLogClass;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Collectors;

import static android.provider.CallLog.Calls.AUTO_MISSED_EMERGENCY_CALL;
import static android.provider.CallLog.Calls.AUTO_MISSED_MAXIMUM_DIALING;
import static android.provider.CallLog.Calls.AUTO_MISSED_MAXIMUM_RINGING;
import static android.provider.CallLog.Calls.USER_MISSED_CALL_FILTERS_TIMEOUT;
import static android.provider.CallLog.Calls.USER_MISSED_CALL_SCREENING_SERVICE_SILENCED;
import static android.provider.CallLog.Calls.USER_MISSED_DND_MODE;
import static android.provider.CallLog.Calls.USER_MISSED_LOW_RING_VOLUME;
import static android.provider.CallLog.Calls.USER_MISSED_NEVER_RANG;
import static android.provider.CallLog.Calls.USER_MISSED_NO_VIBRATE;
import static android.provider.CallLog.Calls.USER_MISSED_SHORT_RING;
import static android.telecom.ParcelableCallAnalytics.AnalyticsEvent;
import static android.telecom.TelecomAnalytics.SessionTiming;

/**
 * A class that collects and stores data on how calls are being made, in order to
 * aggregate these into useful statistics.
 */
public class Analytics {
    public static final String ANALYTICS_DUMPSYS_ARG = "analytics";
    private static final String CLEAR_ANALYTICS_ARG = "clear";

    public static final Map<String, Integer> sLogEventToAnalyticsEvent = Map.ofEntries(
            entry(LogUtils.Events.SET_SELECT_PHONE_ACCOUNT,
                    AnalyticsEvent.SET_SELECT_PHONE_ACCOUNT),
            entry(LogUtils.Events.REQUEST_HOLD, AnalyticsEvent.REQUEST_HOLD),
            entry(LogUtils.Events.REQUEST_UNHOLD, AnalyticsEvent.REQUEST_UNHOLD),
            entry(LogUtils.Events.SWAP, AnalyticsEvent.SWAP),
            entry(LogUtils.Events.SKIP_RINGING, AnalyticsEvent.SKIP_RINGING),
            entry(LogUtils.Events.CONFERENCE_WITH, AnalyticsEvent.CONFERENCE_WITH),
            entry(LogUtils.Events.SPLIT_FROM_CONFERENCE, AnalyticsEvent.SPLIT_CONFERENCE),
            entry(LogUtils.Events.SET_PARENT, AnalyticsEvent.SET_PARENT),
            entry(LogUtils.Events.MUTE, AnalyticsEvent.MUTE),
            entry(LogUtils.Events.UNMUTE, AnalyticsEvent.UNMUTE),
            entry(LogUtils.Events.AUDIO_ROUTE_BT, AnalyticsEvent.AUDIO_ROUTE_BT),
            entry(LogUtils.Events.AUDIO_ROUTE_EARPIECE, AnalyticsEvent.AUDIO_ROUTE_EARPIECE),
            entry(LogUtils.Events.AUDIO_ROUTE_HEADSET, AnalyticsEvent.AUDIO_ROUTE_HEADSET),
            entry(LogUtils.Events.AUDIO_ROUTE_SPEAKER, AnalyticsEvent.AUDIO_ROUTE_SPEAKER),
            entry(LogUtils.Events.SILENCE, AnalyticsEvent.SILENCE),
            entry(LogUtils.Events.SCREENING_COMPLETED, AnalyticsEvent.SCREENING_COMPLETED),
            entry(LogUtils.Events.BLOCK_CHECK_FINISHED, AnalyticsEvent.BLOCK_CHECK_FINISHED),
            entry(LogUtils.Events.DIRECT_TO_VM_FINISHED, AnalyticsEvent.DIRECT_TO_VM_FINISHED),
            entry(LogUtils.Events.REMOTELY_HELD, AnalyticsEvent.REMOTELY_HELD),
            entry(LogUtils.Events.REMOTELY_UNHELD, AnalyticsEvent.REMOTELY_UNHELD),
            entry(LogUtils.Events.REQUEST_PULL, AnalyticsEvent.REQUEST_PULL),
            entry(LogUtils.Events.REQUEST_ACCEPT, AnalyticsEvent.REQUEST_ACCEPT),
            entry(LogUtils.Events.REQUEST_REJECT, AnalyticsEvent.REQUEST_REJECT),
            entry(LogUtils.Events.SET_ACTIVE, AnalyticsEvent.SET_ACTIVE),
            entry(LogUtils.Events.SET_DISCONNECTED, AnalyticsEvent.SET_DISCONNECTED),
            entry(LogUtils.Events.SET_HOLD, AnalyticsEvent.SET_HOLD),
            entry(LogUtils.Events.SET_DIALING, AnalyticsEvent.SET_DIALING),
            entry(LogUtils.Events.START_CONNECTION, AnalyticsEvent.START_CONNECTION),
            entry(LogUtils.Events.BIND_CS, AnalyticsEvent.BIND_CS),
            entry(LogUtils.Events.CS_BOUND, AnalyticsEvent.CS_BOUND),
            entry(LogUtils.Events.SCREENING_SENT, AnalyticsEvent.SCREENING_SENT),
            entry(LogUtils.Events.DIRECT_TO_VM_INITIATED,
                    AnalyticsEvent.DIRECT_TO_VM_INITIATED),
            entry(LogUtils.Events.BLOCK_CHECK_INITIATED, AnalyticsEvent.BLOCK_CHECK_INITIATED),
            entry(LogUtils.Events.FILTERING_INITIATED, AnalyticsEvent.FILTERING_INITIATED),
            entry(LogUtils.Events.FILTERING_COMPLETED, AnalyticsEvent.FILTERING_COMPLETED),
            entry(LogUtils.Events.FILTERING_TIMED_OUT, AnalyticsEvent.FILTERING_TIMED_OUT),
            entry(LogUtils.Events.DND_PRE_CHECK_INITIATED, AnalyticsEvent.DND_CHECK_INITIATED),
            entry(LogUtils.Events.DND_PRE_CHECK_COMPLETED, AnalyticsEvent.DND_CHECK_COMPLETED));

    public static final Map<String, Integer> sLogSessionToSessionId = Map.ofEntries(
            entry(LogUtils.Sessions.ICA_ANSWER_CALL, SessionTiming.ICA_ANSWER_CALL),
            entry(LogUtils.Sessions.ICA_REJECT_CALL, SessionTiming.ICA_REJECT_CALL),
            entry(LogUtils.Sessions.ICA_DISCONNECT_CALL, SessionTiming.ICA_DISCONNECT_CALL),
            entry(LogUtils.Sessions.ICA_HOLD_CALL, SessionTiming.ICA_HOLD_CALL),
            entry(LogUtils.Sessions.ICA_UNHOLD_CALL, SessionTiming.ICA_UNHOLD_CALL),
            entry(LogUtils.Sessions.ICA_MUTE, SessionTiming.ICA_MUTE),
            entry(LogUtils.Sessions.ICA_SET_AUDIO_ROUTE, SessionTiming.ICA_SET_AUDIO_ROUTE),
            entry(LogUtils.Sessions.ICA_CONFERENCE, SessionTiming.ICA_CONFERENCE),
            entry(LogUtils.Sessions.CSW_HANDLE_CREATE_CONNECTION_COMPLETE,
                    SessionTiming.CSW_HANDLE_CREATE_CONNECTION_COMPLETE),
            entry(LogUtils.Sessions.CSW_SET_ACTIVE, SessionTiming.CSW_SET_ACTIVE),
            entry(LogUtils.Sessions.CSW_SET_RINGING, SessionTiming.CSW_SET_RINGING),
            entry(LogUtils.Sessions.CSW_SET_DIALING, SessionTiming.CSW_SET_DIALING),
            entry(LogUtils.Sessions.CSW_SET_DISCONNECTED, SessionTiming.CSW_SET_DISCONNECTED),
            entry(LogUtils.Sessions.CSW_SET_ON_HOLD, SessionTiming.CSW_SET_ON_HOLD),
            entry(LogUtils.Sessions.CSW_REMOVE_CALL, SessionTiming.CSW_REMOVE_CALL),
            entry(LogUtils.Sessions.CSW_SET_IS_CONFERENCED, SessionTiming.CSW_SET_IS_CONFERENCED),
            entry(LogUtils.Sessions.CSW_ADD_CONFERENCE_CALL,
                    SessionTiming.CSW_ADD_CONFERENCE_CALL));

    public static final Map<String, Integer> sLogEventTimingToAnalyticsEventTiming = Map.ofEntries(
            entry(LogUtils.Events.Timings.ACCEPT_TIMING,
                    ParcelableCallAnalytics.EventTiming.ACCEPT_TIMING),
            entry(LogUtils.Events.Timings.REJECT_TIMING,
                    ParcelableCallAnalytics.EventTiming.REJECT_TIMING),
            entry(LogUtils.Events.Timings.DISCONNECT_TIMING,
                    ParcelableCallAnalytics.EventTiming.DISCONNECT_TIMING),
            entry(LogUtils.Events.Timings.HOLD_TIMING,
                    ParcelableCallAnalytics.EventTiming.HOLD_TIMING),
            entry(LogUtils.Events.Timings.UNHOLD_TIMING,
                    ParcelableCallAnalytics.EventTiming.UNHOLD_TIMING),
            entry(LogUtils.Events.Timings.OUTGOING_TIME_TO_DIALING_TIMING,
                    ParcelableCallAnalytics.EventTiming.OUTGOING_TIME_TO_DIALING_TIMING),
            entry(LogUtils.Events.Timings.BIND_CS_TIMING,
                    ParcelableCallAnalytics.EventTiming.BIND_CS_TIMING),
            entry(LogUtils.Events.Timings.SCREENING_COMPLETED_TIMING,
                    ParcelableCallAnalytics.EventTiming.SCREENING_COMPLETED_TIMING),
            entry(LogUtils.Events.Timings.DIRECT_TO_VM_FINISHED_TIMING,
                    ParcelableCallAnalytics.EventTiming.DIRECT_TO_VM_FINISHED_TIMING),
            entry(LogUtils.Events.Timings.BLOCK_CHECK_FINISHED_TIMING,
                    ParcelableCallAnalytics.EventTiming.BLOCK_CHECK_FINISHED_TIMING),
            entry(LogUtils.Events.Timings.FILTERING_COMPLETED_TIMING,
                    ParcelableCallAnalytics.EventTiming.FILTERING_COMPLETED_TIMING),
            entry(LogUtils.Events.Timings.FILTERING_TIMED_OUT_TIMING,
                    ParcelableCallAnalytics.EventTiming.FILTERING_TIMED_OUT_TIMING),
            entry(LogUtils.Events.Timings.START_CONNECTION_TO_REQUEST_DISCONNECT_TIMING,
                    ParcelableCallAnalytics.EventTiming.
                            START_CONNECTION_TO_REQUEST_DISCONNECT_TIMING),
            entry(LogUtils.Events.Timings.DND_PRE_CHECK_COMPLETED_TIMING,
                    ParcelableCallAnalytics.EventTiming.DND_PRE_CALL_PRE_CHECK_TIMING));

    public static final Map<Integer, String> sSessionIdToLogSession = new HashMap<>();

    static {
        for (Map.Entry<String, Integer> e : sLogSessionToSessionId.entrySet()) {
            sSessionIdToLogSession.put(e.getValue(), e.getKey());
        }
    }

    public static class CallInfo {
        public void setCallStartTime(long startTime) {
        }

        public void setCallEndTime(long endTime) {
        }

        public void setCallIsAdditional(boolean isAdditional) {
        }

        public void setCallIsEmergency(boolean isEmergency) {
        }

        public void setCallIsInterrupted(boolean isInterrupted) {
        }

        public void setCallDisconnectCause(DisconnectCause disconnectCause) {
        }

        public void addCallTechnology(int callTechnology) {
        }

        public void setCreatedFromExistingConnection(boolean createdFromExistingConnection) {
        }

        public void setCallConnectionService(String connectionServiceName) {
        }

        public void setCallEvents(EventManager.EventRecord records) {
        }

        public void setCallIsVideo(boolean isVideo) {
        }

        public void addVideoEvent(int eventId, int videoState) {
        }

        public void addInCallService(String serviceName, int type, long boundDuration,
                boolean isNullBinding) {
        }

        public void addCallProperties(int properties) {
        }

        public void setCallSource(int callSource) {
        }

        public void setMissedReason(long missedReason) {
        }
    }

    /**
     * A class that holds data associated with a call.
     */
    @VisibleForTesting
    public static class CallInfoImpl extends CallInfo {
        public String callId;
        public long startTime;  // start time in milliseconds since the epoch. 0 if not yet set.
        public long endTime;  // end time in milliseconds since the epoch. 0 if not yet set.
        public int callDirection;  // one of UNKNOWN_DIRECTION, INCOMING_DIRECTION,
        // or OUTGOING_DIRECTION.
        public boolean isAdditionalCall = false;  // true if the call came in while another call was
        // in progress or if the user dialed this call
        // while in the middle of another call.
        public boolean isInterrupted = false;  // true if the call was interrupted by an incoming
        // or outgoing call.
        public int callTechnologies;  // bitmask denoting which technologies a call used.

        // true if the Telecom Call object was created from an existing connection via
        // CallsManager#createCallForExistingConnection, for example, by ImsConference.
        public boolean createdFromExistingConnection = false;

        public DisconnectCause callTerminationReason;
        public String connectionService;
        public boolean isEmergency = false;

        public EventManager.EventRecord callEvents;

        public boolean isVideo = false;
        public List<TelecomLogClass.VideoEvent> videoEvents;
        public List<TelecomLogClass.InCallServiceInfo> inCallServiceInfos;
        public int callProperties = 0;
        public int callSource = CALL_SOURCE_UNSPECIFIED;
        public long missedReason;

        private long mTimeOfLastVideoEvent = -1;

        CallInfoImpl(String callId, int callDirection) {
            this.callId = callId;
            startTime = 0;
            endTime = 0;
            this.callDirection = callDirection;
            callTechnologies = 0;
            connectionService = "";
            videoEvents = new LinkedList<>();
            inCallServiceInfos = new LinkedList<>();
            missedReason = 0;
        }

        CallInfoImpl(CallInfoImpl other) {
            this.callId = other.callId;
            this.startTime = other.startTime;
            this.endTime = other.endTime;
            this.callDirection = other.callDirection;
            this.isAdditionalCall = other.isAdditionalCall;
            this.isInterrupted = other.isInterrupted;
            this.callTechnologies = other.callTechnologies;
            this.createdFromExistingConnection = other.createdFromExistingConnection;
            this.connectionService = other.connectionService;
            this.isEmergency = other.isEmergency;
            this.callEvents = other.callEvents;
            this.isVideo = other.isVideo;
            this.videoEvents = other.videoEvents;
            this.callProperties = other.callProperties;
            this.callSource = other.callSource;
            this.missedReason = other.missedReason;

            if (other.callTerminationReason != null) {
                this.callTerminationReason = new DisconnectCause(
                        other.callTerminationReason.getCode(),
                        other.callTerminationReason.getLabel(),
                        other.callTerminationReason.getDescription(),
                        other.callTerminationReason.getReason(),
                        other.callTerminationReason.getTone());
            } else {
                this.callTerminationReason = null;
            }
        }

        @Override
        public void setCallStartTime(long startTime) {
            Log.d(TAG, "setting startTime for call " + callId + " to " + startTime);
            this.startTime = startTime;
        }

        @Override
        public void setCallEndTime(long endTime) {
            Log.d(TAG, "setting endTime for call " + callId + " to " + endTime);
            this.endTime = endTime;
        }

        @Override
        public void setCallIsAdditional(boolean isAdditional) {
            Log.d(TAG, "setting isAdditional for call " + callId + " to " + isAdditional);
            this.isAdditionalCall = isAdditional;
        }

        @Override
        public void setCallIsInterrupted(boolean isInterrupted) {
            Log.d(TAG, "setting isInterrupted for call " + callId + " to " + isInterrupted);
            this.isInterrupted = isInterrupted;
        }

        @Override
        public void addCallTechnology(int callTechnology) {
            Log.d(TAG, "adding callTechnology for call " + callId + ": " + callTechnology);
            this.callTechnologies |= callTechnology;
        }

        @Override
        public void setCallIsEmergency(boolean isEmergency) {
            Log.d(TAG, "setting call as emergency: " + isEmergency);
            this.isEmergency = isEmergency;
        }

        @Override
        public void setCallDisconnectCause(DisconnectCause disconnectCause) {
            Log.d(TAG, "setting disconnectCause for call " + callId + " to " + disconnectCause);
            this.callTerminationReason = disconnectCause;
        }

        @Override
        public void setCreatedFromExistingConnection(boolean createdFromExistingConnection) {
            Log.d(TAG, "setting createdFromExistingConnection for call " + callId + " to "
                    + createdFromExistingConnection);
            this.createdFromExistingConnection = createdFromExistingConnection;
        }

        @Override
        public void setCallConnectionService(String connectionServiceName) {
            Log.d(TAG, "setting connection service for call " + callId + ": "
                    + connectionServiceName);
            this.connectionService = connectionServiceName;
        }

        @Override
        public void setMissedReason(long missedReason) {
            Log.d(TAG, "setting missedReason for call " + callId + ": "
                    + missedReason);
            this.missedReason = missedReason;
        }

        @Override
        public void setCallEvents(EventManager.EventRecord records) {
            this.callEvents = records;
        }

        @Override
        public void setCallIsVideo(boolean isVideo) {
            this.isVideo = isVideo;
        }

        @Override
        public void addVideoEvent(int eventId, int videoState) {
            long timeSinceLastEvent;
            long currentTime = System.currentTimeMillis();
            if (mTimeOfLastVideoEvent < 0) {
                timeSinceLastEvent = -1;
            } else {
                timeSinceLastEvent = roundToOneSigFig(currentTime - mTimeOfLastVideoEvent);
            }
            mTimeOfLastVideoEvent = currentTime;

            videoEvents.add(new TelecomLogClass.VideoEvent()
                    .setEventName(eventId)
                    .setTimeSinceLastEventMillis(timeSinceLastEvent)
                    .setVideoState(videoState));
        }

        @Override
        public void addInCallService(String serviceName, int type, long boundDuration,
                boolean isNullBinding) {
            inCallServiceInfos.add(new TelecomLogClass.InCallServiceInfo()
                    .setInCallServiceName(serviceName)
                    .setInCallServiceType(type)
                    .setBoundDurationMillis(boundDuration)
                    .setIsNullBinding(isNullBinding));
        }

        @Override
        public void addCallProperties(int properties) {
            this.callProperties |= properties;
        }

        @Override
        public void setCallSource(int callSource) {
            this.callSource = callSource;
        }

        @Override
        public String toString() {
            return "{\n"
                    + "    startTime: " + startTime + '\n'
                    + "    endTime: " + endTime + '\n'
                    + "    direction: " + getCallDirectionString() + '\n'
                    + "    isAdditionalCall: " + isAdditionalCall + '\n'
                    + "    isInterrupted: " + isInterrupted + '\n'
                    + "    isEmergency: " + isEmergency + '\n'
                    + "    callTechnologies: " + getCallTechnologiesAsString() + '\n'
                    + "    callTerminationReason: " + getCallDisconnectReasonString() + '\n'
                    + "    missedReason: " + getMissedReasonString() + '\n'
                    + "    connectionService: " + connectionService + '\n'
                    + "    isVideoCall: " + isVideo + '\n'
                    + "    inCallServices: " + getInCallServicesString() + '\n'
                    + "    callProperties: " + Connection.propertiesToStringShort(callProperties)
                    + '\n'
                    + "    callSource: " + getCallSourceString() + '\n'
                    + "}\n";
        }

        public ParcelableCallAnalytics toParcelableAnalytics() {
            TelecomLogClass.CallLog analyticsProto = toProto();
            List<ParcelableCallAnalytics.AnalyticsEvent> events =
                    Arrays.stream(analyticsProto.callEvents)
                            .map(callEventProto -> new ParcelableCallAnalytics.AnalyticsEvent(
                                    callEventProto.getEventName(),
                                    callEventProto.getTimeSinceLastEventMillis())
                            ).collect(Collectors.toList());

            List<ParcelableCallAnalytics.EventTiming> timings =
                    Arrays.stream(analyticsProto.callTimings)
                            .map(callTimingProto -> new ParcelableCallAnalytics.EventTiming(
                                    callTimingProto.getTimingName(),
                                    callTimingProto.getTimeMillis())
                            ).collect(Collectors.toList());

            ParcelableCallAnalytics result = new ParcelableCallAnalytics(
                    // rounds down to nearest 5 minute mark
                    analyticsProto.getStartTime5Min(),
                    analyticsProto.getCallDurationMillis(),
                    analyticsProto.getType(),
                    analyticsProto.getIsAdditionalCall(),
                    analyticsProto.getIsInterrupted(),
                    analyticsProto.getCallTechnologies(),
                    analyticsProto.getCallTerminationCode(),
                    analyticsProto.getIsEmergencyCall(),
                    analyticsProto.connectionService[0],
                    analyticsProto.getIsCreatedFromExistingConnection(),
                    events,
                    timings);

            result.setIsVideoCall(analyticsProto.getIsVideoCall());
            result.setVideoEvents(Arrays.stream(analyticsProto.videoEvents)
                    .map(videoEventProto -> new ParcelableCallAnalytics.VideoEvent(
                            videoEventProto.getEventName(),
                            videoEventProto.getTimeSinceLastEventMillis(),
                            videoEventProto.getVideoState())
                    ).collect(Collectors.toList()));

            result.setCallSource(analyticsProto.getCallSource());

            return result;
        }

        public TelecomLogClass.CallLog toProto() {
            TelecomLogClass.CallLog result = new TelecomLogClass.CallLog();
            result.setStartTime5Min(
                    startTime - startTime % ParcelableCallAnalytics.MILLIS_IN_5_MINUTES);

            // Rounds up to the nearest second.
            long callDuration = (endTime == 0 || startTime == 0) ? 0 : endTime - startTime;
            callDuration += (callDuration % MILLIS_IN_1_SECOND == 0) ?
                    0 : (MILLIS_IN_1_SECOND - callDuration % MILLIS_IN_1_SECOND);
            result.setCallDurationMillis(callDuration);

            result.setType(callDirection)
                    .setIsAdditionalCall(isAdditionalCall)
                    .setIsInterrupted(isInterrupted)
                    .setCallTechnologies(callTechnologies)
                    .setCallTerminationCode(
                            callTerminationReason == null ?
                                    ParcelableCallAnalytics.STILL_CONNECTED :
                                    callTerminationReason.getCode())
                    .setIsEmergencyCall(isEmergency)
                    .setIsCreatedFromExistingConnection(createdFromExistingConnection)
                    .setIsEmergencyCall(isEmergency)
                    .setIsVideoCall(isVideo)
                    .setConnectionProperties(callProperties)
                    .setCallSource(callSource);

            result.connectionService = new String[]{connectionService};
            if (callEvents != null) {
                result.callEvents = convertLogEventsToProtoEvents(callEvents.getEvents());
                result.callTimings = callEvents.extractEventTimings().stream()
                        .map(Analytics::logEventTimingToProtoEventTiming)
                        .toArray(TelecomLogClass.EventTimingEntry[]::new);
            }
            result.videoEvents =
                    videoEvents.toArray(new TelecomLogClass.VideoEvent[videoEvents.size()]);
            result.inCallServices = inCallServiceInfos.toArray(
                    new TelecomLogClass.InCallServiceInfo[inCallServiceInfos.size()]);

            return result;
        }

        private String getCallDirectionString() {
            switch (callDirection) {
                case UNKNOWN_DIRECTION:
                    return "UNKNOWN";
                case INCOMING_DIRECTION:
                    return "INCOMING";
                case OUTGOING_DIRECTION:
                    return "OUTGOING";
                default:
                    return "UNKNOWN";
            }
        }

        private String getCallTechnologiesAsString() {
            StringBuilder s = new StringBuilder();
            s.append('[');
            if ((callTechnologies & CDMA_PHONE) != 0) s.append("CDMA ");
            if ((callTechnologies & GSM_PHONE) != 0) s.append("GSM ");
            if ((callTechnologies & SIP_PHONE) != 0) s.append("SIP ");
            if ((callTechnologies & IMS_PHONE) != 0) s.append("IMS ");
            if ((callTechnologies & THIRD_PARTY_PHONE) != 0) s.append("THIRD_PARTY ");
            s.append(']');
            return s.toString();
        }

        private String getCallDisconnectReasonString() {
            if (callTerminationReason != null) {
                return callTerminationReason.toString();
            } else {
                return "NOT SET";
            }
        }

        private String getMissedReasonString() {
            StringBuilder s =  new StringBuilder();
            s.append('[');
            if ((missedReason & AUTO_MISSED_EMERGENCY_CALL) != 0) {
                s.append("emergency]");
                return s.toString();
            } else if ((missedReason & AUTO_MISSED_MAXIMUM_DIALING) != 0) {
                s.append("max_dialing]");
                return s.toString();
            } else if ((missedReason & AUTO_MISSED_MAXIMUM_RINGING) != 0) {
                s.append("max_ringing]");
                return s.toString();
            }

            // user missed
            if ((missedReason & USER_MISSED_SHORT_RING) != 0) s.append("short_ring ");
            if ((missedReason & USER_MISSED_DND_MODE) != 0) s.append("dnd ");
            if ((missedReason & USER_MISSED_LOW_RING_VOLUME) != 0) s.append("low_volume ");
            if ((missedReason & USER_MISSED_NO_VIBRATE) != 0) s.append("no_vibrate ");
            if ((missedReason & USER_MISSED_CALL_SCREENING_SERVICE_SILENCED) != 0)
                s.append("css_silenced ");
            if ((missedReason & USER_MISSED_CALL_FILTERS_TIMEOUT) != 0) s.append("filter_timeout ");
            if ((missedReason & USER_MISSED_NEVER_RANG) != 0) s.append("no_ring ");
            s.append("]");
            return s.toString();
        }

        private String getInCallServicesString() {
            StringBuilder s = new StringBuilder();
            s.append("[\n");
            if (inCallServiceInfos != null) {
                for (TelecomLogClass.InCallServiceInfo service : inCallServiceInfos) {
                    s.append("    ");
                    s.append("name: ");
                    s.append(service.getInCallServiceName());
                    s.append(" type: ");
                    s.append(service.getInCallServiceType());
                    s.append(" is crashed: ");
                    s.append(service.getIsNullBinding());
                    s.append(" service last time in ms: ");
                    s.append(service.getBoundDurationMillis());
                    s.append("\n");
                }
            }
            s.append("]");
            return s.toString();
        }

        private String getCallSourceString() {
            switch (callSource) {
                case CALL_SOURCE_UNSPECIFIED:
                    return "UNSPECIFIED";
                case CALL_SOURCE_EMERGENCY_DIALPAD:
                    return "EMERGENCY_DIALPAD";
                case CALL_SOURCE_EMERGENCY_SHORTCUT:
                    return "EMERGENCY_SHORTCUT";
                default:
                    return "UNSPECIFIED";
            }
        }
    }

    public static final String TAG = "TelecomAnalytics";

    // Constants for call direction
    public static final int UNKNOWN_DIRECTION = ParcelableCallAnalytics.CALLTYPE_UNKNOWN;
    public static final int INCOMING_DIRECTION = ParcelableCallAnalytics.CALLTYPE_INCOMING;
    public static final int OUTGOING_DIRECTION = ParcelableCallAnalytics.CALLTYPE_OUTGOING;

    // Constants for call technology
    public static final int CDMA_PHONE = ParcelableCallAnalytics.CDMA_PHONE;
    public static final int GSM_PHONE = ParcelableCallAnalytics.GSM_PHONE;
    public static final int IMS_PHONE = ParcelableCallAnalytics.IMS_PHONE;
    public static final int SIP_PHONE = ParcelableCallAnalytics.SIP_PHONE;
    public static final int THIRD_PARTY_PHONE = ParcelableCallAnalytics.THIRD_PARTY_PHONE;

    // Constants for call source
    public static final int CALL_SOURCE_UNSPECIFIED =
            TelecomManager.CALL_SOURCE_UNSPECIFIED;
    public static final int CALL_SOURCE_EMERGENCY_DIALPAD =
            TelecomManager.CALL_SOURCE_EMERGENCY_DIALPAD;
    public static final int CALL_SOURCE_EMERGENCY_SHORTCUT =
            TelecomManager.CALL_SOURCE_EMERGENCY_SHORTCUT;

    // Constants for video events
    public static final int SEND_LOCAL_SESSION_MODIFY_REQUEST =
            ParcelableCallAnalytics.VideoEvent.SEND_LOCAL_SESSION_MODIFY_REQUEST;
    public static final int SEND_LOCAL_SESSION_MODIFY_RESPONSE =
            ParcelableCallAnalytics.VideoEvent.SEND_LOCAL_SESSION_MODIFY_RESPONSE;
    public static final int RECEIVE_REMOTE_SESSION_MODIFY_REQUEST =
            ParcelableCallAnalytics.VideoEvent.RECEIVE_REMOTE_SESSION_MODIFY_REQUEST;
    public static final int RECEIVE_REMOTE_SESSION_MODIFY_RESPONSE =
            ParcelableCallAnalytics.VideoEvent.RECEIVE_REMOTE_SESSION_MODIFY_RESPONSE;

    public static final long MILLIS_IN_1_SECOND = ParcelableCallAnalytics.MILLIS_IN_1_SECOND;

    public static final int MAX_NUM_CALLS_TO_STORE = 100;
    public static final int MAX_NUM_DUMP_TIMES_TO_STORE = 100;

    private static final Object sLock = new Object(); // Coarse lock for all of analytics
    private static final LinkedBlockingDeque<Long> sDumpTimes =
            new LinkedBlockingDeque<>(MAX_NUM_DUMP_TIMES_TO_STORE);
    private static final Map<String, CallInfoImpl> sCallIdToInfo = new HashMap<>();
    private static final LinkedList<String> sActiveCallIds = new LinkedList<>();
    private static final List<SessionTiming> sSessionTimings = new LinkedList<>();

    public static void addSessionTiming(String sessionName, long time) {
        if (sLogSessionToSessionId.containsKey(sessionName)) {
            synchronized (sLock) {
                sSessionTimings.add(new SessionTiming(sLogSessionToSessionId.get(sessionName),
                        time));
            }
        }
    }

    public static CallInfo initiateCallAnalytics(String callId, int direction) {
        Log.i(TAG, "Starting analytics for call " + callId);
        CallInfoImpl callInfo = new CallInfoImpl(callId, direction);
        synchronized (sLock) {
            while (sActiveCallIds.size() >= MAX_NUM_CALLS_TO_STORE) {
                String callToRemove = sActiveCallIds.remove();
                sCallIdToInfo.remove(callToRemove);
            }
            sCallIdToInfo.put(callId, callInfo);
            sActiveCallIds.add(callId);
        }
        return callInfo;
    }

    public static TelecomAnalytics dumpToParcelableAnalytics() {
        List<ParcelableCallAnalytics> calls = new LinkedList<>();
        List<SessionTiming> sessionTimings = new LinkedList<>();
        synchronized (sLock) {
            calls.addAll(sCallIdToInfo.values().stream()
                    .map(CallInfoImpl::toParcelableAnalytics)
                    .collect(Collectors.toList()));
            sessionTimings.addAll(sSessionTimings);
            sCallIdToInfo.clear();
            sSessionTimings.clear();
        }
        return new TelecomAnalytics(sessionTimings, calls);
    }

    public static void dumpToEncodedProto(Context context, PrintWriter pw, String[] args) {
        TelecomLogClass.TelecomLog result = new TelecomLogClass.TelecomLog();

        synchronized (sLock) {
            noteDumpTime();
            result.callLogs = sCallIdToInfo.values().stream()
                    .map(CallInfoImpl::toProto)
                    .toArray(TelecomLogClass.CallLog[]::new);
            result.sessionTimings = sSessionTimings.stream()
                    .map(timing -> new TelecomLogClass.LogSessionTiming()
                            .setSessionEntryPoint(timing.getKey())
                            .setTimeMillis(timing.getTime()))
                    .toArray(TelecomLogClass.LogSessionTiming[]::new);
            result.setHardwareRevision(SystemProperties.get("ro.boot.revision", ""));
            result.setCarrierId(getCarrierId(context));
            if (args.length > 1 && CLEAR_ANALYTICS_ARG.equals(args[1])) {
                sCallIdToInfo.clear();
                sSessionTimings.clear();
            }
        }
        String encodedProto = Base64.encodeToString(
                TelecomLogClass.TelecomLog.toByteArray(result), Base64.DEFAULT);
        pw.write(encodedProto);
    }

    private static int getCarrierId(Context context) {
        try {
            SubscriptionManager subscriptionManager =
                    context.getSystemService(SubscriptionManager.class).createForAllUserProfiles();
            List<SubscriptionInfo> subInfos = subscriptionManager.getActiveSubscriptionInfoList();
            if (subInfos == null) {
                return -1;
            }
            return subInfos.stream()
                    .max(Comparator.comparing(Analytics::scoreSubscriptionInfo))
                    .map(SubscriptionInfo::getCarrierId).orElse(-1);
        } catch (UnsupportedOperationException ignored) {
            return -1;
        }
    }

    // Copied over from Telephony's server-side logic for consistency
    private static int scoreSubscriptionInfo(SubscriptionInfo subInfo) {
        final int scoreCarrierId = 0b100;
        final int scoreNotOpportunistic = 0b010;
        final int scoreSlot0 = 0b001;

        return ((subInfo.getCarrierId() >= 0) ? scoreCarrierId : 0)
                + (subInfo.isOpportunistic() ? 0 : scoreNotOpportunistic)
                + ((subInfo.getSimSlotIndex() == 0) ? scoreSlot0 : 0);
    }

    public static void dump(IndentingPrintWriter writer) {
        synchronized (sLock) {
            int prefixLength = CallsManager.TELECOM_CALL_ID_PREFIX.length();
            List<String> callIds = new ArrayList<>(sCallIdToInfo.keySet());
            // Sort the analytics in increasing order of call IDs
            try {
                Collections.sort(callIds, (id1, id2) -> {
                    int i1, i2;
                    try {
                        i1 = Integer.valueOf(id1.substring(prefixLength));
                    } catch (NumberFormatException e) {
                        i1 = Integer.MAX_VALUE;
                    }

                    try {
                        i2 = Integer.valueOf(id2.substring(prefixLength));
                    } catch (NumberFormatException e) {
                        i2 = Integer.MAX_VALUE;
                    }
                    return i1 - i2;
                });
            } catch (IllegalArgumentException e) {
                // do nothing, leave the list in a partially sorted state.
            }

            for (String callId : callIds) {
                writer.printf("Call %s: ", callId);
                writer.println(sCallIdToInfo.get(callId).toString());
            }

            Map<Integer, Double> averageTimings = SessionTiming.averageTimings(sSessionTimings);
            averageTimings.entrySet().stream()
                    .filter(e -> sSessionIdToLogSession.containsKey(e.getKey()))
                    .forEach(e -> writer.printf("%s: %.2f\n",
                            sSessionIdToLogSession.get(e.getKey()), e.getValue()));
            writer.println("Hardware Version: " + SystemProperties.get("ro.boot.revision", ""));
            writer.println("Past analytics dumps: ");
            writer.increaseIndent();
            for (long time : sDumpTimes) {
                writer.println(Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC));
            }
            writer.decreaseIndent();
        }
    }

    public static void reset() {
        synchronized (sLock) {
            sCallIdToInfo.clear();
        }
    }

    public static void noteDumpTime() {
        if (sDumpTimes.remainingCapacity() == 0) {
            sDumpTimes.removeLast();
        }
        try {
            sDumpTimes.addFirst(System.currentTimeMillis());
        } catch (IllegalStateException e) {
            Log.w(TAG, "Failed to note dump time -- full");
        }
    }

    /**
     * Returns a copy of callIdToInfo. Use only for testing.
     */
    @VisibleForTesting
    public static Map<String, CallInfoImpl> cloneData() {
        synchronized (sLock) {
            Map<String, CallInfoImpl> result = new HashMap<>(sCallIdToInfo.size());
            for (Map.Entry<String, CallInfoImpl> entry : sCallIdToInfo.entrySet()) {
                result.put(entry.getKey(), new CallInfoImpl(entry.getValue()));
            }
            return result;
        }
    }

    private static TelecomLogClass.Event[] convertLogEventsToProtoEvents(
            List<EventManager.Event> logEvents) {
        long timeOfLastEvent = -1;
        ArrayList<TelecomLogClass.Event> events = new ArrayList<>(logEvents.size());
        for (EventManager.Event logEvent : logEvents) {
            if (sLogEventToAnalyticsEvent.containsKey(logEvent.eventId)) {
                TelecomLogClass.Event event = new TelecomLogClass.Event();
                event.setEventName(sLogEventToAnalyticsEvent.get(logEvent.eventId));
                event.setTimeSinceLastEventMillis(roundToOneSigFig(
                        timeOfLastEvent < 0 ? -1 : logEvent.time - timeOfLastEvent));
                events.add(event);
                timeOfLastEvent = logEvent.time;
            }
        }
        return events.toArray(new TelecomLogClass.Event[events.size()]);
    }

    private static TelecomLogClass.EventTimingEntry logEventTimingToProtoEventTiming(
            EventManager.EventRecord.EventTiming logEventTiming) {
        int analyticsEventTimingName =
                sLogEventTimingToAnalyticsEventTiming.containsKey(logEventTiming.name) ?
                        sLogEventTimingToAnalyticsEventTiming.get(logEventTiming.name) :
                        ParcelableCallAnalytics.EventTiming.INVALID;
        return new TelecomLogClass.EventTimingEntry()
                .setTimingName(analyticsEventTimingName)
                .setTimeMillis(logEventTiming.time);
    }

    @VisibleForTesting
    public static long roundToOneSigFig(long val) {
        if (val == 0) {
            return val;
        }
        int logVal = (int) Math.floor(Math.log10(val < 0 ? -val : val));
        double s = Math.pow(10, logVal);
        double dec = val / s;
        return (long) (Math.round(dec) * s);
    }
}
