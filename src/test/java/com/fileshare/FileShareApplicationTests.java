package com.fileshare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FileShareApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring application context starts without errors
    }
}
