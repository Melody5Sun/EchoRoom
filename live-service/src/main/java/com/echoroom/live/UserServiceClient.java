package com.echoroom.live;

import java.util.List;

import com.echoroom.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "echoroom-user", path = "/api/v1/users")
public interface UserServiceClient {
    @GetMapping("/{userId}")
    ApiResponse<UserProfileSnapshot> getProfile(@PathVariable("userId") long userId);

    @GetMapping("/internal/batch")
    ApiResponse<List<UserProfileSnapshot>> getProfiles(@RequestParam("ids") List<Long> userIds);

    @GetMapping("/internal/banned-count")
    ApiResponse<Long> countBannedUsers();
}
