# 交互式静态演示

在浏览器中打开 [index.html](index.html) 即可体验。演示版无需账号、Node.js 服务或数据库，使用虚构数据展示核心家庭照护流程，与完整 Vue 应用相互独立。

演示版支持家庭成员切换、照护任务、用药确认与库存联动、血压录入、最近 7/30 次读数趋势、示例健康档案、CSV 导出和一键重置。数据仅保留在当前页面中，刷新后即清除。通知、OCR、上传、AI 和家庭邀请均不会连接真实服务；内容安全策略会阻止所有网络连接。

## 使用 GitHub Pages 发布

仓库已包含 [GitHub Pages 工作流](../.github/workflows/demo-pages.yml)。

1. 将项目推送到目标 GitHub 仓库。
2. 打开 **Settings → Pages → Build and deployment → Source**，选择 **GitHub Actions**。
3. 运行 **Actions → Publish static demo → Run workflow**。之后 `main` 或 `master` 分支下 `demo/` 的变更会自动部署。
4. 工作流完成后，打开 **Open live demo** 链接或仓库的 `github-pages` 部署地址。
5. 确定最终仓库名称后，将公开演示地址补充到主 README。

发布内容仅包括静态页面、脚本、样式和演示图片，不会上传后端配置、SQL 文件、测试及仓库中的其他内容。

GitHub 参考文档：[通过自定义工作流使用 GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)。

## 验证

```bash
node --test demo/tests/*.test.mjs
node --check demo/app.js
```

浏览器验收时，请依次切换家庭成员；完成用药任务并核对库存；新增血压读数并核对表格、图表和任务状态；打开一份档案；导出 CSV；最后刷新或重置页面，确认虚构数据恢复初始状态。

`assets/` 下的图片来自项目自有资源，以确保演示版可以独立部署。产品标志或插图更新时，请同步更新对应的演示资源。
