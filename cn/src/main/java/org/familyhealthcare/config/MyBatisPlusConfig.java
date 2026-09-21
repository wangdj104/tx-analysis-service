package org.familyhealthcare.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.*;


/**
 * @Description: MyBatisPlusConfig
 * @Author: jiazs10
 * @CreateTime: 2023/11/23 9:55
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * MyBatisPlusinterceptor (used forpointpage)
     */
    @Bean
    public MybatisPlusInterceptor paginationInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        //AddMySQL pointpageinterceptor
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
