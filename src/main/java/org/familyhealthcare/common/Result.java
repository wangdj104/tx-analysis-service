package org.familyhealthcare.common;

import lombok.Data;

import java.io.Serializable;

/**
 * systemoneBackresult
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String msg;
    private T data;
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMsg(MessageLocalizer.localize("success"));
        if (data instanceof String) {
            @SuppressWarnings("unchecked")
            T localized = (T) MessageLocalizer.localize((String) data);
            r.setData(localized);
        } else {
            r.setData(data);
        }
        return r;
    }

    public static <T> Result<T> error(String msg) {
        return error(500, msg);
    }

    public static <T> Result<T> error(int code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(MessageLocalizer.localize(msg));
        return r;
    }
}
