package com.todollab.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Test
    void malformedJsonBodyReturns400ValidationErrorInsteadOf500() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidUuidPathVariableReturns400ValidationErrorInsteadOf500() throws Exception {
        mockMvc.perform(get("/test/items/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.itemId").isNotEmpty());
    }

    @Test
    void missingRequestParameterReturns400ValidationErrorInsteadOf500() throws Exception {
        mockMvc.perform(get("/test/search"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.q").isNotEmpty());
    }

    @Test
    void handlerMethodValidationExceptionReturns400ValidationErrorInsteadOf500() throws Exception {
        mockMvc.perform(get("/test/validated-param").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.limit").isNotEmpty());
    }

    @Test
    void unsupportedHttpMethodReturns405InsteadOf500() throws Exception {
        mockMvc.perform(delete("/test/validation"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void noResourceFoundExceptionReturns404InsteadOf500() throws Exception {
        mockMvc.perform(get("/test/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void dataIntegrityViolationReturns409ConflictWithoutLeakingInternalDetails() throws Exception {
        ProblemDetail problem = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("could not execute statement; constraint [uq_users_email]")
        );

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail())
                .isEqualTo("Conflito de dados: o registro já existe ou viola uma restrição de integridade.");
        assertThat(problem.getProperties()).containsEntry("code", "CONFLICT");

        mockMvc.perform(get("/test/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail")
                        .value("Conflito de dados: o registro já existe ou viola uma restrição de integridade."))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("uq_users_email")
                        .doesNotContain("could not execute"));
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

        @GetMapping("/test/items/{itemId}")
        void getItem(@PathVariable UUID itemId) {
        }

        @GetMapping("/test/search")
        void search(@RequestParam String q) {
        }

        @GetMapping("/test/validated-param")
        void validatedParam(@RequestParam @Min(value = 1, message = "Limite deve ser >= 1") int limit) {
        }

        @GetMapping("/test/missing-resource")
        void missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/missing-resource");
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

        @GetMapping("/test/data-integrity")
        void throwDataIntegrity() {
            throw new DataIntegrityViolationException("could not execute statement; constraint [uq_users_email]");
        }
    }
}
