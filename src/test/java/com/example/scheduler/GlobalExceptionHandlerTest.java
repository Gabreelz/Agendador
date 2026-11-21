package com.example.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.scheduler.config.TraceIdFilter;
import com.example.scheduler.exception.BusinessException;
import com.example.scheduler.exception.ConflictException;
import com.example.scheduler.exception.GlobalExceptionHandler;
import com.example.scheduler.exception.NotFoundException;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-04: testa o mapeamento de cada erro usando MockMvc em modo standalone
 * (sem contexto Spring completo, sem banco, sem Docker).
 */
class GlobalExceptionHandlerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new FakeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new TraceIdFilter())
                .build();
    }

    // ---------------------------------------------------------------- 400

    @Test
    void validationErrorReturns400ListingEveryInvalidField() throws Exception {
        ResultActions result = mvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"age\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/test/validation"))
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder("name", "age")))
                .andExpect(jsonPath("$.details[*].message", not(empty())));
        assertStandardShape(result);
    }

    @Test
    void malformedJsonReturns400WithoutLeakingParserInternals() throws Exception {
        ResultActions result = mvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{nome: isto não é json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not(containsString("com.fasterxml"))))
                .andExpect(jsonPath("$.message", not(containsString("JsonParseException"))));
        assertStandardShape(result);
    }

    @Test
    void wrongParameterTypeReturns400WithParameterName() throws Exception {
        ResultActions result = mvc.perform(get("/test/param").param("count", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("count"));
        assertStandardShape(result);
    }

    // ---------------------------------------------------------------- 403 / 404 / 405

    @Test
    void notFoundReturns404() throws Exception {
        ResultActions result = mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Job 42 não encontrado"));
        assertStandardShape(result);
    }

    @Test
    void unsupportedMethodReturns405InTheSameFormat() throws Exception {
        ResultActions result = mvc.perform(post("/test/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
        assertStandardShape(result);
    }

    @Test
    void accessDeniedThrownInControllerReturns403() throws Exception {
        ResultActions result = mvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Acesso negado."));
        assertStandardShape(result);
    }

    // ---------------------------------------------------------------- 409

    @Test
    void conflictExceptionReturns409() throws Exception {
        ResultActions result = mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Já existe um job com este nome"));
        assertStandardShape(result);
    }

    @Test
    void optimisticLockReturns409() throws Exception {
        ResultActions result = mvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        assertStandardShape(result);
    }

    @Test
    void uniqueViolationReturns409WithoutExposingConstraintOrSql() throws Exception {
        ResultActions result = mvc.perform(get("/test/unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", not(containsString("job_name_key"))))
                .andExpect(jsonPath("$.message", not(containsString("INSERT"))));
        assertStandardShape(result);
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("job_name_key", "INSERT INTO", "duplicate key");
    }

    // ---------------------------------------------------------------- 422

    @Test
    void businessRuleViolationReturns422() throws Exception {
        ResultActions result = mvc.perform(get("/test/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Job DISABLED não pode ser retomado"));
        assertStandardShape(result);
    }

    // ---------------------------------------------------------------- 500

    @Test
    void unexpectedErrorReturns500WithoutInternalInformation() throws Exception {
        ResultActions result = mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.traceId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        assertStandardShape(result);

        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "IllegalStateException", "segredo-do-banco", "jdbc:postgresql",
                "com.example.scheduler", "java.lang", "\tat ");
    }

    // ---------------------------------------------------------------- traceId

    @Test
    void traceIdInBodyMatchesResponseHeader() throws Exception {
        var response = mvc.perform(get("/test/boom")).andReturn().getResponse();
        String header = response.getHeader(TraceIdFilter.HEADER);
        assertThat(header).isNotBlank();
        mvc.perform(get("/test/boom"))
                .andExpect(header().exists(TraceIdFilter.HEADER));
        assertThat(response.getContentAsString()).contains("\"traceId\":\"" + header + "\"");
    }

    // ---------------------------------------------------------------- helpers

    /** Todo erro precisa ter exatamente o mesmo formato. */
    private static void assertStandardShape(ResultActions result) throws Exception {
        result.andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.path").isString())
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.details").isArray());
        String contentType = result.andReturn().getResponse().getContentType();
        assertThat(contentType).startsWith("application/json");
    }

    // ---------------------------------------------------------------- controller falso

    record Payload(@NotBlank String name, @Min(1) int age) {
    }

    @RestController
    @RequestMapping("/test")
    static class FakeController {

        @PostMapping("/validation")
        String validation(@jakarta.validation.Valid @RequestBody Payload payload) {
            return "ok";
        }

        @GetMapping("/param")
        String param(@RequestParam int count) {
            return "ok";
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new NotFoundException("Job 42 não encontrado");
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new AccessDeniedException("detalhe interno do Spring Security");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ConflictException("Já existe um job com este nome");
        }

        @GetMapping("/optimistic-lock")
        String optimisticLock() {
            throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
        }

        @GetMapping("/unique")
        String unique() {
            throw new DuplicateKeyException(
                    "ERROR: duplicate key value violates unique constraint \"job_name_key\" "
                            + "INSERT INTO job (name) VALUES ('x')");
        }

        @GetMapping("/business")
        String business() {
            throw new BusinessException("Job DISABLED não pode ser retomado");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException(
                    "falha ao conectar jdbc:postgresql://db:5432/app senha=segredo-do-banco");
        }
    }
}