package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.service.workflow.WorkflowException;
import java.util.function.Supplier;

/** 只标记已分派到 QUESTION 的失败，避免把库存等旧入口错误翻译成问题错误。 */
public final class VisitQuestionFailure extends RuntimeException {
    private final WorkflowException failure;
    public VisitQuestionFailure(WorkflowException failure){super(failure.getMessage(),failure);this.failure=failure;}
    public int getStatus(){return failure.getStatus();}
    public String getErrorCode(){return failure.getErrorCode();}
    public static <T> T expose(Supplier<T> operation){
        try{return operation.get();}
        catch(WorkflowException failure){throw new VisitQuestionFailure(failure);}
    }
}
