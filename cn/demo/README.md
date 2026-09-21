# 交互式静态演示

在浏览器中打开 [index.html](index.html)，或访问[中文在线演示](https://wangdj104.github.io/tx-analysis-service/cn/)。演示版无需账号、应用服务或数据库，使用虚构数据，与完整 Vue 应用相互独立。

演示版可切换医生、患者和家属身份，覆盖患者数据范围、医生复核、照护计划、照护任务、用药打卡与库存、血压趋势、医疗记录、透析、营养、复诊、家庭交接、CSV 导出和重置。数据仅保留在当前标签页；模拟的复核和 AI 标识不会调用真实服务，内容安全策略会阻止网络连接。

## 使用 GitHub Pages 发布

仓库已包含 [GitHub Pages 工作流](../../.github/workflows/demo-pages.yml)。

1. 将项目推送到目标 GitHub 仓库。
2. 打开 **Settings → Pages → Build and deployment → Source**，选择 **GitHub Actions**。
3. 运行 **Actions → Publish static demo → Run workflow**。之后 `main` 分支下 `demo/` 或 `cn/demo/` 的变更会自动部署。
4. 英文版发布在 Pages 根路径，中文版发布在 `/cn/`。

发布内容仅包括静态页面、脚本、样式和演示图片，不会上传后端配置、SQL 文件、测试及仓库中的其他内容。

GitHub 参考文档：[通过自定义工作流使用 GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)。

## 验证

```bash
node --test demo/tests/*.test.mjs
node --check demo/app.js
```

浏览器验收时，请切换三种身份和不同患者；通过或驳回一项复核；新增照护计划；完成用药任务并核对库存；新增血压读数并核对表格、图表和任务状态；新增家庭交接；打开档案；导出 CSV；最后重置演示。

`assets/` 下的图片来自项目自有资源，以确保演示版可以独立部署。产品标志或插图更新时，请同步更新对应的演示资源。
