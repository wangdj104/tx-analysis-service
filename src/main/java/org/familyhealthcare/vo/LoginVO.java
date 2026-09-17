package org.familyhealthcare.vo;

import lombok.Data;
import javax.validation.constraints.NotBlank;

/**
 * Sign InrequestVO
 */
@Data
public class LoginVO {

    @NotBlank(message = "Usernamecannot be empty")
    private String username;

    @NotBlank(message = "Passwordcannot be empty")
    private String password;
}
