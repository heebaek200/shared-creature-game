package com.team.sharedcreature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SharedcreatureApplicationTests {

	@Test
	void contextLoads() {
	}

}
