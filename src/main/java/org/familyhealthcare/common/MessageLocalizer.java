package org.familyhealthcare.common;

import org.springframework.context.i18n.LocaleContextHolder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Localizes API envelope messages while keeping protocol fields and database codes stable. */
final class MessageLocalizer {
    private static final Map<String, String> ZH = new LinkedHashMap<>();

    static {
        ZH.put("success", "成功");
        ZH.put("Saved successfully", "保存成功");
        ZH.put("Failed to save", "保存失败");
        ZH.put("Updated successfully", "更新成功");
        ZH.put("Update failed", "更新失败");
        ZH.put("Deleted successfully", "删除成功");
        ZH.put("Failed to delete", "删除失败");
        ZH.put("Created successfully", "创建成功");
        ZH.put("Operation completed", "操作成功");
        ZH.put("Operation failed", "操作失败");
        ZH.put("Deleted", "已删除");
        ZH.put("Not signed in", "尚未登录");
        ZH.put("User not found", "用户不存在");
        ZH.put("Role not found", "角色不存在");
        ZH.put("The record does not exist.", "记录不存在。");
        ZH.put("Select a patient", "请选择患者");
        ZH.put("Invalid request parameter.", "请求参数无效。");
        ZH.put("Invalid date range", "日期范围无效");
        ZH.put("Invalid status.", "状态无效。");
        ZH.put("The account is disabled.", "账号已停用。");
        ZH.put("Username or Passworderror", "用户名或密码错误");
        ZH.put("Password reset successfully", "密码重置成功");
        ZH.put("Password reset failed", "密码重置失败");
        ZH.put("Record verified", "记录已核验");
        ZH.put("Record rejected", "记录已驳回");
        ZH.put("Removed successfully.", "移除成功。");
        ZH.put("Inventory settings saved.", "库存设置已保存。");
        ZH.put("Restock recorded.", "补货记录已保存。");
        ZH.put("Recorded successfully.", "记录成功。");
        ZH.put("Configure a valid webhook URL.", "请配置有效的 Webhook 地址。");
        ZH.put("The webhook URL must begin with http:// or https://.", "Webhook 地址必须以 http:// 或 https:// 开头。");
        ZH.put("Unsupported notification channel type.", "不支持的通知渠道类型。");
        ZH.put("Preview the data and explicitly confirm the import first.", "请先预览数据并明确确认导入。");
        ZH.put("There are no records to save.", "没有可保存的记录。");
        ZH.put("No recognizable image was found.", "未找到可识别的图片。");
        ZH.put("Word recognition is not supported yet. Upload an image or PDF.", "暂不支持 Word 识别，请上传图片或 PDF。");
        ZH.put("Vital-sign records could not be loaded. Verify that the database is initialized.", "生命体征记录加载失败，请确认数据库已完成初始化。");
        ZH.put("Complication records could not be loaded. Verify that the database is initialized.", "并发症记录加载失败，请确认数据库已完成初始化。");
        ZH.put("Complication statistics could not be loaded. Verify that the database is initialized.", "并发症统计加载失败，请确认数据库已完成初始化。");
        ZH.put("Nutrition DiaryFailed to load, Please Confirm nutrition_diary tablestructureUpdated successfully", "营养日记加载失败，请确认 nutrition_diary 表结构已完成更新。");
        ZH.put("The patient does not exist.", "患者不存在。");
        ZH.put("The alert does not exist.", "提醒不存在。");
        ZH.put("The task status changed. Refresh and try again.", "任务状态已变化，请刷新后重试。");
        ZH.put("This task has been cancelled.", "该任务已取消。");
        ZH.put("This task has already been processed. Refresh the page.", "该任务已处理，请刷新页面。");
        ZH.put("A target minimum cannot exceed its maximum.", "目标最小值不能大于最大值。");
        ZH.put("Enter an event title.", "请输入事件标题。");
        ZH.put("The title may contain up to 120 characters and the description up to 500.", "标题最多 120 个字符，描述最多 500 个字符。");
        ZH.put("Invalid event type.", "事件类型无效。");
        ZH.put("Select an event date.", "请选择事件日期。");
        ZH.put("Invalid event time format.", "事件时间格式无效。");
        ZH.put("The event does not exist.", "事件不存在。");
        ZH.put("The event does not exist or belongs to another patient.", "事件不存在或属于其他患者。");
        ZH.put("Edit this item from its original record page.", "请前往原始记录页面编辑此项目。");
        ZH.put("Delete this item from its original record page.", "请前往原始记录页面删除此项目。");
        ZH.put("Medication not found", "药品不存在");
        ZH.put("Medication list cannot be empty", "药品列表不能为空");
        ZH.put("Upload at least one file.", "请至少上传一个文件。");
        ZH.put("Upload no more than 10 files at a time.", "每次最多上传 10 个文件。");
        ZH.put("Image data cannot be empty", "图片数据不能为空");
        ZH.put("batchSaved successfully", "批量保存成功");
        ZH.put("batchFailed to save", "批量保存失败");
        ZH.put("Confirmsuccessful", "确认成功");
        ZH.put("resolvesuccessful", "处理成功");
        ZH.put("alertExaminationcomplete", "提醒检查完成");
        ZH.put("Password update failed", "密码更新失败");
        ZH.put("Role assigned successfully", "角色分配成功");
        ZH.put("You do not have permission to ActionsRole", "无权操作该角色");
        ZH.put("adminRolecannot Edit", "管理员角色不可编辑");
        ZH.put("You do not have permission to DeleteRole", "无权删除该角色");
        ZH.put("adminRolecannot Delete", "管理员角色不可删除");
        ZH.put("You do not have permission to assignMenu", "无权分配菜单");
        ZH.put("adminRole MenuPermissioncannot in pageEdit, for example needadjustPlease Manualrefreshdatabase", "管理员角色的菜单权限不能在页面中编辑，如需调整请手动更新数据库。");
        ZH.put("Menuassignsuccessful", "菜单分配成功");
        ZH.put("You do not have permission to Createuser", "无权创建用户");
        ZH.put("Usernamealready storein ", "用户名已存在");
        ZH.put("You do not have permission to EditOtheruserinformation", "无权编辑其他用户信息");
        ZH.put("You do not have permission to Deleteuser", "无权删除用户");
        ZH.put("adminusercannot Delete", "管理员用户不可删除");
        ZH.put("You do not have permission to ResetPassword", "无权重置密码");
        ZH.put("PasswordUpdated successfully, Please sign in again", "密码更新成功，请重新登录");
        ZH.put("You do not have permission to assignRole", "无权分配角色");
        ZH.put("The system could not process the request. Please try again later.", "系统暂时无法处理该请求，请稍后重试。");
        ZH.put("A required parameter is missing or has an invalid format.", "必填参数缺失或格式无效。");
    }

    private MessageLocalizer() {}

    static String localize(String message) {
        if (message == null || message.trim().isEmpty() || !isChineseRequest()) return message;
        String exact = ZH.get(message);
        if (exact != null) return exact;
        if (message.startsWith("Failed to save: ")) return "保存失败：" + message.substring(16);
        if (message.startsWith("Update failed: ")) return "更新失败：" + message.substring(15);
        if (message.startsWith("Failed to load: ")) return "加载失败：" + message.substring(16);
        if (message.startsWith("Failed to read file: ")) return "读取文件失败：" + message.substring(21);
        if (message.startsWith("Failed to parse file: ")) return "解析文件失败：" + message.substring(22);
        if (message.startsWith("Unsupported data type: ")) return "不支持的数据类型：" + message.substring(23);
        if (message.startsWith("Unsupported file format: ")) return "不支持的文件格式：" + message.substring(25);
        if (message.startsWith("Notification delivery failed: ")) return "通知发送失败：" + message.substring(30);
        return message;
    }

    private static boolean isChineseRequest() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && Locale.CHINESE.getLanguage().equals(locale.getLanguage());
    }
}
