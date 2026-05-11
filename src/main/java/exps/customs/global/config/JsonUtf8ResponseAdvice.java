package exps.customs.global.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.nio.charset.StandardCharsets;

@RestControllerAdvice
public class JsonUtf8ResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null) {
            contentType = selectedContentType;
        }
        if (contentType == null) {
            return body;
        }

        boolean isJson =
                MediaType.APPLICATION_JSON.includes(contentType) ||
                (contentType.getSubtype() != null && contentType.getSubtype().endsWith("+json"));
        if (!isJson) {
            return body;
        }

        if (contentType.getCharset() == null) {
            response.getHeaders().setContentType(
                    new MediaType(contentType.getType(), contentType.getSubtype(), StandardCharsets.UTF_8)
            );
        }
        return body;
    }
}
