package com.echoroom.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.echoroom.realtime", "com.echoroom.gateway.support"})
@EnableDiscoveryClient
public class RealtimeGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(RealtimeGatewayApplication.class, args);
    }
}
