package org.familyhealthcare.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * master-cn 分支的统一接口消息本地化器。
 *
 * <p>业务状态码和持久化枚举继续使用稳定的英文代码，接口中的人类可读消息在输出前统一转换为中文，
 * 以兼容已有客户端和数据库数据。</p>
 */
final class ChineseMessageLocalizer {
    private static final Map<String, String> EXACT = new LinkedHashMap<>();

    static {
        EXACT.put("success", "成功");
        EXACT.put("Saved successfully", "保存成功");
        EXACT.put("Failed to save", "保存失败");
        EXACT.put("Updated successfully", "更新成功");
        EXACT.put("Update failed", "更新失败");
        EXACT.put("Deleted successfully", "删除成功");
        EXACT.put("Failed to delete", "删除失败");
        EXACT.put("Created successfully", "创建成功");
        EXACT.put("Operation completed", "操作成功");
        EXACT.put("Operation failed", "操作失败");
        EXACT.put("Deleted", "已删除");
        EXACT.put("Not signed in", "尚未登录");
        EXACT.put("User not found", "用户不存在");
        EXACT.put("Role not found", "角色不存在");
        EXACT.put("The record does not exist.", "记录不存在。");
        EXACT.put("Select a patient", "请选择患者");
        EXACT.put("Invalid request parameter.", "请求参数无效。");
        EXACT.put("Invalid date range", "日期范围无效");
        EXACT.put("Invalid status.", "状态无效。");
        EXACT.put("The account is disabled.", "账号已停用。");
        EXACT.put("Username or Passworderror", "用户名或密码错误");
        EXACT.put("Password reset successfully", "密码重置成功");
        EXACT.put("Password reset failed", "密码重置失败");
        EXACT.put("Record verified", "记录已核验");
        EXACT.put("Record rejected", "记录已驳回");
        EXACT.put("Removed successfully.", "移除成功。");
        EXACT.put("Inventory settings saved.", "库存设置已保存。");
        EXACT.put("Restock recorded.", "补货记录已保存。");
        EXACT.put("Recorded successfully.", "记录成功。");
        EXACT.put("Configure a valid webhook URL.", "请配置有效的 Webhook 地址。");
        EXACT.put("The webhook URL must begin with http:// or https://.", "Webhook 地址必须以 http:// 或 https:// 开头。");
        EXACT.put("Unsupported notification channel type.", "不支持的通知渠道类型。");
        EXACT.put("Preview the data and explicitly confirm the import first.", "请先预览数据并明确确认导入。");
        EXACT.put("There are no records to save.", "没有可保存的记录。");
        EXACT.put("No recognizable image was found.", "未找到可识别的图片。");
        EXACT.put("Word recognition is not supported yet. Upload an image or PDF.", "暂不支持 Word 识别，请上传图片或 PDF。");
        EXACT.put("Vital-sign records could not be loaded. Verify that the database is initialized.", "生命体征记录加载失败，请确认数据库已完成初始化。");
        EXACT.put("Complication records could not be loaded. Verify that the database is initialized.", "并发症记录加载失败，请确认数据库已完成初始化。");
        EXACT.put("Complication statistics could not be loaded. Verify that the database is initialized.", "并发症统计加载失败，请确认数据库已完成初始化。");
        EXACT.put("Nutrition DiaryFailed to load, Please Confirm nutrition_diary tablestructureUpdated successfully", "营养日记加载失败，请确认 nutrition_diary 表结构已完成更新。");
        EXACT.put("The patient does not exist.", "患者不存在。");
        EXACT.put("The alert does not exist.", "提醒不存在。");
        EXACT.put("The task status changed. Refresh and try again.", "任务状态已变化，请刷新后重试。");
        EXACT.put("This task has been cancelled.", "该任务已取消。");
        EXACT.put("This task has already been processed. Refresh the page.", "该任务已处理，请刷新页面。");
        EXACT.put("A target minimum cannot exceed its maximum.", "目标最小值不能大于最大值。");
        EXACT.put("Enter an event title.", "请输入事件标题。");
        EXACT.put("The title may contain up to 120 characters and the description up to 500.", "标题最多 120 个字符，描述最多 500 个字符。");
        EXACT.put("Invalid event type.", "事件类型无效。");
        EXACT.put("Select an event date.", "请选择事件日期。");
        EXACT.put("Invalid event time format.", "事件时间格式无效。");
        EXACT.put("The event does not exist.", "事件不存在。");
        EXACT.put("The event does not exist or belongs to another patient.", "事件不存在或属于其他患者。");
        EXACT.put("Edit this item from its original record page.", "请前往原始记录页面编辑此项目。");
        EXACT.put("Delete this item from its original record page.", "请前往原始记录页面删除此项目。");
        EXACT.put("Medication not found", "药品不存在");
        EXACT.put("Medication list cannot be empty", "药品列表不能为空");
        EXACT.put("Upload at least one file.", "请至少上传一个文件。");
        EXACT.put("Upload no more than 10 files at a time.", "每次最多上传 10 个文件。");
        EXACT.put("Image data cannot be empty", "图片数据不能为空");
        EXACT.put("batchSaved successfully", "批量保存成功");
        EXACT.put("batchFailed to save", "批量保存失败");
        EXACT.put("Confirmsuccessful", "确认成功");
        EXACT.put("resolvesuccessful", "处理成功");
        EXACT.put("alertExaminationcomplete", "提醒检查完成");
        EXACT.put("You do not have permission to ActionsRole", "无权操作该角色");
        EXACT.put("adminRolecannot Edit", "管理员角色不可编辑");
        EXACT.put("You do not have permission to DeleteRole", "无权删除该角色");
        EXACT.put("adminRolecannot Delete", "管理员角色不可删除");
        EXACT.put("You do not have permission to assignMenu", "无权分配菜单");
        EXACT.put("adminRole MenuPermissioncannot in pageEdit, for example needadjustPlease Manualrefreshdatabase", "管理员角色的菜单权限不能在页面中编辑，如需调整请手动更新数据库。");
        EXACT.put("Menuassignsuccessful", "菜单分配成功");
        EXACT.put("You do not have permission to Createuser", "无权创建用户");
        EXACT.put("Usernamealready storein ", "用户名已存在");
        EXACT.put("You do not have permission to EditOtheruserinformation", "无权编辑其他用户信息");
        EXACT.put("You do not have permission to Deleteuser", "无权删除用户");
        EXACT.put("adminusercannot Delete", "管理员用户不可删除");
        EXACT.put("You do not have permission to ResetPassword", "无权重置密码");
        EXACT.put("PasswordUpdated successfully, Please sign in again", "密码更新成功，请重新登录");
        EXACT.put("Password update failed", "密码更新失败");
        EXACT.put("You do not have permission to assignRole", "无权分配角色");
        EXACT.put("Role assigned successfully", "角色分配成功");
    }

    private ChineseMessageLocalizer() {}

    static String localize(String message) {
        if (message == null || message.isBlank()) return message;
        String exact = EXACT.get(message);
        if (exact != null) return exact;
        if (message.startsWith("Failed to save: ")) return "保存失败：" + message.substring(16);
        if (message.startsWith("Update failed: ")) return "更新失败：" + message.substring(15);
        if (message.startsWith("Failed to load: ")) return "加载失败：" + message.substring(16);
        if (message.startsWith("Failed to read file: ")) return "读取文件失败：" + message.substring(21);
        if (message.startsWith("Failed to parse file: ")) return "解析文件失败：" + message.substring(22);
        if (message.startsWith("Saved ") && message.endsWith(" records")) {
            return "已保存 " + message.substring(6, message.length() - 8) + " 条记录";
        }
        if (message.startsWith("Unsupported data type: ")) return "不支持的数据类型：" + message.substring(23);
        if (message.startsWith("Unsupported file format: ")) return "不支持的文件格式：" + message.substring(25);
        if (message.startsWith("Notification delivery failed: ")) return "通知发送失败：" + message.substring(30);
        return message;
    }
}
