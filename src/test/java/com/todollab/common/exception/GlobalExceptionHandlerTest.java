package com.todollab.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void apiExceptionProducesProblemDetailWithCodeAndStatus() throws Exception {
        var ex = new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN_ROLE, "Sem permissão");
        ProblemDetail problem = handler.handleApiException(ex);

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getDetail()).isEqualTo("Sem permissão");
        assertThat(problem.getProperties()).containsEntry("code", "FORBIDDEN_ROLE");

        mockMvc.perform(get("/test/api-exception"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Sem permissão"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void validationExceptionPopulatesFieldErrorsAndValidationCode() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"code\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.code").isNotEmpty());
    }

    @Test
    void validationExceptionHandlesMultipleViolationsOnSameFieldGracefully() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ok@example.com\",\"code\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.code").isNotEmpty());
    }

    @Test
    void authenticationExceptionReturns401UnauthorizedProblemDetail() throws Exception {
        ProblemDetail problem = handler.handleAuthenticationException(new BadCredentialsException("Bad credentials"));

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getProperties()).containsEntry("code", "UNAUTHORIZED");

        mockMvc.perform(get("/test/auth-exception"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void accessDeniedExceptionReturns403ForbiddenRoleProblemDetail() throws Exception {
        ProblemDetail problem = handler.handleAccessDeniedException(new AccessDeniedException("Denied"));

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getProperties()).containsEntry("code", "FORBIDDEN_ROLE");

        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void unexpectedExceptionReturns500InternalErrorWithoutLeakingInternalDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-db-connection-string"));
    }

    record SamplePayload(
            @NotBlank(message = "E-mail é obrigatório") String email,
            @Size(min = 6, max = 6, message = "Código deve ter 6 caracteres")
            @Pattern(regexp = "^[0-9]+$", message = "Código deve conter apenas dígitos")
            String code
    ) {
    }

    @RestController
    static class StubController {
        @GetMapping("/test/api-exception")
        void throwApiException() {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN_ROLE, "Sem permissão");
        }

        @PostMapping("/test/validation")
        void validate(@Valid @RequestBody SamplePayload payload) {
        }

        @GetMapping("/test/auth-exception")
        void throwAuthException() {
            throw new BadCredentialsException("Bad credentials");
        }

        @GetMapping("/test/access-denied")
        void throwAccessDenied() {
            throw new AccessDeniedException("Forbidden");
        }

        @GetMapping("/test/unexpected")
        void throwUnexpected() {
            throw new IllegalStateException("secret-db-connection-string");
        }
    }
}
