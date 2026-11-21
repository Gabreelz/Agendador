package com.example.scheduler.exception;

import com.example.scheduler.config.TraceIdFilter;
import com.example.scheduler.dto.ErrorResponse;
import com.example.scheduler.dto.ErrorResponse.Detail;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;


@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    // ---------------------------------------------------------------- exceções de domínio

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), List.of(), ex, request, HttpHeaders.EMPTY);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), List.of(), ex, request, HttpHeaders.EMPTY);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex, WebRequest request) {
        return respond(HttpStatus.CONFLICT, ex.getMessage(), List.of(), ex, request, HttpHeaders.EMPTY);
    }

    // ---------------------------------------------------------------- 409: banco de dados

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<Object> handleOptimisticLock(Exception ex, WebRequest request) {
        return respond(HttpStatus.CONFLICT,
                "O recurso foi alterado por outro usuário. Recarregue os dados e tente novamente.",
                List.of(), ex, request, HttpHeaders.EMPTY);
    }

    /** Unique, chave estrangeira, not null etc. A mensagem do banco NUNCA vai para a resposta. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        return respond(HttpStatus.CONFLICT,
                "A operação conflita com os dados existentes (ex.: valor duplicado ou registro em uso).",
                List.of(), ex, request, HttpHeaders.EMPTY);
    }

    // ---------------------------------------------------------------- 400: validação

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Detail> details = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> details.add(new Detail(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> details.add(new Detail(e.getObjectName(), e.getDefaultMessage())));
        return respond(HttpStatus.BAD_REQUEST, "Dados da requisição inválidos.", details, ex, request, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Detail> details = new ArrayList<>();
        ex.getValueResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(e -> details.add(new Detail(name, e.getDefaultMessage())));
        });
        return respond(HttpStatus.BAD_REQUEST, "Parâmetros da requisição inválidos.", details, ex, request, headers);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<Detail> details = ex.getConstraintViolations().stream()
                .map(v -> new Detail(lastNode(v.getPropertyPath()), v.getMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "Dados inválidos.", details, ex, request, HttpHeaders.EMPTY);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // A mensagem do Jackson cita classes internas: não é repassada.
        return respond(HttpStatus.BAD_REQUEST, "Corpo da requisição ausente, malformado ou com tipos inválidos.",
                List.of(), ex, request, headers);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Parâmetro obrigatório ausente.",
                List.of(new Detail(ex.getParameterName(), "Parâmetro obrigatório")), ex, request, headers);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Valor de parâmetro inválido.",
                List.of(new Detail(ex.getPropertyName(), "Valor com formato ou tipo inválido")),
                ex, request, headers);
    }

    // ---------------------------------------------------------------- segurança (lançadas em controllers)

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(HttpStatus.FORBIDDEN, "Acesso negado.", List.of(), ex, request, HttpHeaders.EMPTY);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "Autenticação necessária.", List.of(), ex, request,
                HttpHeaders.EMPTY);
    }

    // ---------------------------------------------------------------- erros padrão do Spring MVC

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        return respond(statusCode, defaultMessage(statusCode), List.of(), ex, request, headers);
    }

    // ---------------------------------------------------------------- 500: qualquer outra coisa

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro interno inesperado. Informe o traceId ao suporte.", List.of(), ex, request,
                HttpHeaders.EMPTY);
    }

    // ---------------------------------------------------------------- infraestrutura

    private ResponseEntity<Object> respond(HttpStatusCode status, String message, List<Detail> details,
                                           Exception ex, WebRequest request, HttpHeaders headers) {
        String path = path(request);
        String traceId = traceId();

        if (status.is5xxServerError()) {
            // Único lugar com stack trace: o log (o traceId já vem pelo MDC).
            log.error("Erro {} em {} {}", status.value(), method(request), path, ex);
        } else {
            log.warn("Erro {} em {} {}: {}", status.value(), method(request), path, ex.getClass().getSimpleName());
            log.debug("Detalhe técnico do erro {}", status.value(), ex);
        }

        HttpStatus resolved = HttpStatus.resolve(status.value());
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                resolved != null ? resolved.getReasonPhrase() : "Error",
                message,
                path,
                traceId,
                details);

        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static String defaultMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Requisição inválida.";
            case 404 -> "Recurso não encontrado.";
            case 405 -> "Método HTTP não suportado para este endpoint.";
            case 406 -> "Formato de resposta não suportado.";
            case 415 -> "Tipo de conteúdo não suportado.";
            default -> status.is5xxServerError()
                    ? "Erro interno inesperado. Informe o traceId ao suporte."
                    : "Não foi possível processar a requisição.";
        };
    }

    private static String traceId() {
        String id = MDC.get(TraceIdFilter.MDC_KEY);
        return id != null ? id : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
    }

    private static String method(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getMethod() : "";
    }

    private static String lastNode(Path propertyPath) {
        String last = "";
        for (Path.Node node : propertyPath) {
            last = node.getName();
        }
        return last;
    }
}