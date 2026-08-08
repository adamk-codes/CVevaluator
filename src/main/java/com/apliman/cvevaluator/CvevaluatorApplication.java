package com.apliman.cvevaluator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @EnableAsync} is what makes CvIngestionService's {@code @Async}
 * listener actually leave the request thread. Without it the annotation is
 * inert - the code still runs and every test still passes, it just runs
 * synchronously. Worth knowing, because nothing fails to tell you.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class CvevaluatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(CvevaluatorApplication.class, args);
	}

}
