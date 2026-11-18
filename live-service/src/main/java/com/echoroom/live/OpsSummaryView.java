package com.echoroom.live;

public record OpsSummaryView(
        long liveRoomCount,
        long onlineCount,
        long giftOrderCount,
        long activityOrderCount,
        long pendingOrderCount,
        long bannedUserCount) {
}
