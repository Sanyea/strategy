package com.sanye.strategy.common.exception;

import com.sanye.strategy.common.response.R;
import com.sanye.strategy.common.response.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * <p>
 * 全局异常处理器 — 统一将异常转换为 {@code R<T>} 响应，并按码段返回对应 HTTP 状态码
 * </p>
 * <p>
 * 职责映射：
 * <ul>
 *   <li>{@link BizException} → 携带的状态码（默认或自定义提示语），自动解析 HTTP 状态</li>
 *   <li>参数校验（@Valid / ConstraintViolation / Bind / 缺参 / 请求体解析） → 400</li>
 *   <li>方法不支持 / 媒体类型不支持 / 文件超限 → 405 / 415 / 413</li>
 *   <li>数据库约束冲突 → 409</li>
 *   <li>静态资源 404 → 404</li>
 *   <li>兜底异常 → 500（记完整堆栈，返回通用提示，不泄露内部信息）</li>
 * </ul>
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：前端控制器（Front Controller）式收敛层，异常处理集中一处。</li>
 *   <li>优缺点：Controller/Service 免 try-catch，响应格式与 HTTP 状态码统一，日志带请求上下文；
 *       缺点：依赖异常匹配顺序（具体优先于泛型），兜底分支必须记录完整堆栈防止吞异常。</li>
 *   <li>约定：业务可控错误抛 {@link BizException}；HTTP 状态由 ResultCode 码段推导，业务码与 HTTP 语义对齐。</li>
 * </ul>
 * </p>
 *
 * @author 31372
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    /**
     * 业务异常
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Object>> handleBizException(HttpServletRequest request, BizException e) {
        ResultCode resultCode = e.getResultCode();
        String message = e.getMessage();
        if (resultCode == null) {
            // 防御：状态码缺失时按 500 处理，避免异常处理自身 NPE
            log.error("业务异常缺少状态码, request={}, message={}", requestLine(request), message);
            return response(ResultCode.INTERNAL_ERROR, ResultCode.INTERNAL_ERROR.getMessage());
        }
        log.warn("业务异常 {}: code={}, message={}", requestLine(request), resultCode.getCode(), message);
        if (e.getPayload() != null) {
            HttpStatus httpStatus = resolveHttpStatus(resultCode);
            return ResponseEntity.status(httpStatus).body(R.fail(resultCode, message, e.getPayload()));
        }
        return response(resultCode, message);
    }

    // ==================== 参数校验（400） ====================

    /**
     * 参数校验失败（@RequestBody @Valid）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValid(HttpServletRequest request,
                                                                MethodArgumentNotValidException e) {
        String message = buildMessage(e.getBindingResult());
        log.warn("参数校验失败 {}: {}", requestLine(request), message);
        return response(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 参数校验失败（方法参数 @Validated / 校验注解）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(HttpServletRequest request,
                                                             ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {}: {}", requestLine(request), message);
        return response(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 参数绑定失败（表单绑定）
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(HttpServletRequest request, BindException e) {
        String message = buildMessage(e.getBindingResult());
        log.warn("参数绑定失败 {}: {}", requestLine(request), message);
        return response(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 缺少必要请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParameter(HttpServletRequest request,
                                                          MissingServletRequestParameterException e) {
        String message = "缺少必要参数: " + e.getParameterName();
        log.warn("缺少必要参数 {}: {}", requestLine(request), e.getParameterName());
        return response(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 请求体解析失败
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleMessageNotReadable(HttpServletRequest request,
                                                            HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 {}: {}", requestLine(request), e.getMessage());
        return response(ResultCode.BAD_REQUEST, "请求体解析失败");
    }

    // ==================== HTTP 协议相关 ====================

    /**
     * 请求方法不支持（405）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpServletRequest request,
                                                            HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 {}: {}", requestLine(request), e.getMethod());
        return response(ResultCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
    }

    /**
     * 不支持的媒体类型（415）
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMediaTypeNotSupported(HttpServletRequest request,
                                                               HttpMediaTypeNotSupportedException e) {
        log.warn("不支持的媒体类型 {}: {}", requestLine(request), e.getContentType());
        return response(ResultCode.UNSUPPORTED_MEDIA_TYPE, "不支持的媒体类型: " + e.getContentType());
    }

    /**
     * 文件超出大小限制（413）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleMaxUploadSize(HttpServletRequest request,
                                                       MaxUploadSizeExceededException e) {
        log.warn("文件超出大小限制 {}: {}", requestLine(request), e.getMessage());
        return response(ResultCode.PAYLOAD_TOO_LARGE);
    }

    // ==================== 数据冲突（409） ====================

    /**
     * 唯一键冲突
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<R<Void>> handleDuplicateKey(HttpServletRequest request, DuplicateKeyException e) {
        log.warn("唯一键冲突 {}: {}", requestLine(request), e.getMessage());
        return response(ResultCode.CONFLICT, "数据已存在，请勿重复提交");
    }

    /**
     * 数据完整性冲突
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<R<Void>> handleDataIntegrityViolation(HttpServletRequest request,
                                                                DataIntegrityViolationException e) {
        log.warn("数据完整性冲突 {}: {}", requestLine(request), e.getMessage());
        return response(ResultCode.CONFLICT, "数据冲突，操作未执行");
    }

    // ==================== 404 / 兜底 ====================

    /**
     * 资源不存在（404）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFound(HttpServletRequest request, NoResourceFoundException e) {
        return response(ResultCode.NOT_FOUND);
    }

    /**
     * 兜底异常 — 记录完整堆栈，返回通用 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(HttpServletRequest request, Exception e) {
        log.error("系统异常 {}", requestLine(request), e);
        return response(ResultCode.INTERNAL_ERROR);
    }

    // ==================== 私有辅助 ====================

    /**
     * 组装校验错误信息（字段错误 + 类级别错误）
     */
    private String buildMessage(org.springframework.validation.BindingResult bindingResult) {
        return bindingResult.getAllErrors().stream()
                .map(error -> {
                    String prefix = error instanceof FieldError ? ((FieldError) error).getField() + ": " : "";
                    String defaultMessage = error.getDefaultMessage();
                    return prefix + (defaultMessage == null ? error.getCode() : defaultMessage);
                })
                .collect(Collectors.joining("; "));
    }

    /**
     * 按状态码返回对应的 HTTP 状态码与响应体
     */
    private <T> ResponseEntity<R<T>> response(ResultCode resultCode, String message) {
        HttpStatus httpStatus = resolveHttpStatus(resultCode);
        return ResponseEntity.status(httpStatus).body(R.fail(resultCode, message));
    }

    private <T> ResponseEntity<R<T>> response(ResultCode resultCode) {
        return response(resultCode, resultCode.getMessage());
    }

    /**
     * 状态码 → HTTP 状态码（码段与 HTTP 语义对齐，可直接取值）
     */
    private HttpStatus resolveHttpStatus(ResultCode resultCode) {
        if (resultCode == null || resultCode.isSuccess()) {
            return HttpStatus.OK;
        }
        return HttpStatus.valueOf(resultCode.getCode());
    }

    /**
     * 请求上下文行（方法 + 路径），用于日志排错
     */
    private String requestLine(HttpServletRequest request) {
        if (request == null) {
            return "-";
        }
        return request.getMethod() + " " + request.getRequestURI();
    }
}
