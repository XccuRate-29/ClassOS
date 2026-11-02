package com.classos.attendance;

import com.classos.config.ClassosProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Base64;

@Configuration
public class AttendanceConfig {

    /** Campus time: "today" for attendance means today in India, wherever the server runs. */
    public static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    public AttendanceCodes attendanceCodes(ClassosProperties props) {
        ClassosProperties.Attendance cfg = props.attendance();
        String secret = cfg == null ? null : cfg.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("ATTENDANCE_SECRET is not set (see .env.example)");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ATTENDANCE_SECRET must be base64-encoded", e);
        }
        return new AttendanceCodes(key, cfg.windowSeconds());
    }

    @Bean
    public CidrMatcher campusNetwork(ClassosProperties props) {
        return new CidrMatcher(props.attendance().allowedCidrs());
    }

    @Bean
    public Clock campusClock() {
        return Clock.system(CAMPUS_ZONE);
    }
}
