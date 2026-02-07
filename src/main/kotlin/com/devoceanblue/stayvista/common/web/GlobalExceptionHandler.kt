package com.devoceanblue.stayvista.common.web

import com.devoceanblue.stayvista.common.api.ApiErrorBody
import com.devoceanblue.stayvista.common.api.ApiResponses
import com.devoceanblue.stayvista.common.api.DomainException
import com.devoceanblue.stayvista.common.api.ErrorCode
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(DomainException::class)
    fun handleDomainException(ex: DomainException): ResponseEntity<Any> {
        return response(
            status = ex.errorCode.httpStatus,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = ex.errorCode.code,
                    message = ex.message,
                    details = ex.details,
                ),
            ),
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<Any> {
        val fieldErrors = ex.bindingResult.fieldErrors.associateBy(
            keySelector = FieldError::getField,
            valueTransform = FieldError::getDefaultMessage,
        )
        return response(
            status = HttpStatus.BAD_REQUEST,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = ErrorCode.VALIDATION_ERROR.code,
                    message = "Validation failed",
                    details = mapOf("fields" to fieldErrors),
                ),
            ),
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<Any> {
        val details = ex.constraintViolations.associate { violation ->
            violation.propertyPath.toString() to violation.message
        }
        return response(
            status = HttpStatus.BAD_REQUEST,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = ErrorCode.VALIDATION_ERROR.code,
                    message = "Validation failed",
                    details = mapOf("violations" to details),
                ),
            ),
        )
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<Any> {
        return response(
            status = HttpStatus.BAD_REQUEST,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = ErrorCode.VALIDATION_ERROR.code,
                    message = "Invalid request parameter",
                    details = mapOf("parameter" to ex.name),
                ),
            ),
        )
    }

    @ExceptionHandler(EntityNotFoundException::class)
    fun handleEntityNotFound(ex: EntityNotFoundException): ResponseEntity<Any> {
        return response(
            status = HttpStatus.NOT_FOUND,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = ErrorCode.NOT_FOUND.code,
                    message = ex.message ?: "Resource not found",
                ),
            ),
        )
    }

    @ExceptionHandler(DuplicateKeyException::class, DataIntegrityViolationException::class)
    fun handleConflict(ex: RuntimeException): ResponseEntity<Any> {
        return response(
            status = HttpStatus.CONFLICT,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = ErrorCode.CONFLICT.code,
                    message = "Conflict",
                    details = mapOf("reason" to (ex.message ?: "data integrity violation")),
                ),
            ),
        )
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<Any> {
        return response(
            status = HttpStatus.METHOD_NOT_ALLOWED,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = "METHOD_NOT_ALLOWED",
                    message = ex.message ?: "Method not allowed",
                ),
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnhandled(ex: Exception): ResponseEntity<Any> {
        return response(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            body = ApiResponses.error(
                ApiErrorBody(
                    code = ErrorCode.INTERNAL.code,
                    message = "Unexpected internal error",
                    details = mapOf("type" to ex.javaClass.simpleName),
                ),
            ),
        )
    }

    private fun response(status: HttpStatus, body: Any): ResponseEntity<Any> {
        return ResponseEntity.status(status).body(body)
    }
}
