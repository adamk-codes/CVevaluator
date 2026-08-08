package com.apliman.cvevaluator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CvevaluatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(CvevaluatorApplication.class, args);

	}

}
