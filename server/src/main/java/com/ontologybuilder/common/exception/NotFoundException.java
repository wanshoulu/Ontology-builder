package com.ontologybuilder.common.exception;

/**
 * 资源未找到异常（404）
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(404, message);
    }

    public NotFoundException(String resource, String id) {
        super(404, String.format("%s 未找到: %s", resource, id));
    }
}
