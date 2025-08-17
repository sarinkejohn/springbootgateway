package dev.sarinkejohn.gateway_demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
		"d.s.gateway_demo",                // your main package
		"dev.sarinkejohn.gateway.filter"   // package with the filter
})
public class GatewayDemoApplication {
	public static void main(String[] args) {
		SpringApplication.run(GatewayDemoApplication.class, args);
	}

	//we can do this with full qualified java API  like
//	@Bean
//	public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
//		return builder.routes()
//				.route(r -> r.path("/posts/**")
//						.filters(f -> f
//							.prefixPath("/api")
//							.addResponseHeader("X-Powered-By","sarinkeJohn Gateway Service")
//						)
//						.uri("http://localhost:8080")
//				)
//				.route(r -> r.path("/transactions/**")
//						.filters(f -> f
//								.prefixPath("/api")
//								.addResponseHeader("X-Powered-By","sarinkeJohn Gateway Service")
//						)
//						.uri("http://localhost:8082")
//				)
//				.build();
//	}
}

































