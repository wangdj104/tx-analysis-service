<template>
  <div class="branding-page">
    <header class="branding-heading"><div><span>平台设置</span><h1>平台品牌</h1><p>统一配置登录页、桌面端、移动端和页面所有权信息。</p></div><el-button type="primary" :loading="saving" @click="save">保存并应用</el-button></header>
    <div class="branding-layout">
      <el-card shadow="never" class="branding-form-card">
        <el-form label-position="top">
          <el-form-item label="平台名称（包括晨星健康／澄心健康等）"><el-input v-model="form.platformName" maxlength="80" show-word-limit /></el-form-item>
          <el-form-item label="公司或医院名称"><el-input v-model="form.organizationName" maxlength="120" show-word-limit placeholder="显示在平台名称下方" /></el-form-item>
          <el-form-item label="平台 Logo">
            <div class="logo-editor"><img :src="safeLogo" alt="Logo 预览" /><div><el-upload :show-file-list="false" :auto-upload="false" accept="image/png,image/jpeg,image/webp,image/svg+xml" :on-change="selectLogo"><el-button>选择图片</el-button></el-upload><small>支持 PNG、JPEG、WebP、SVG，最大 1 MB。</small></div></div>
            <el-input v-model="form.logo" placeholder="也可输入站内路径或 HTTPS 图片地址" />
          </el-form-item>
          <el-form-item label="页面背景色"><div class="color-editor"><el-color-picker v-model="form.pageBackground" /><el-input v-model="form.pageBackground" maxlength="7" /></div></el-form-item>
          <el-form-item label="页面正中间的所有权文字"><el-input v-model="form.ownershipText" type="textarea" :rows="3" maxlength="240" show-word-limit /></el-form-item>
        </el-form>
      </el-card>
      <section class="branding-preview" :style="{ backgroundColor: validColor }" aria-label="品牌实时预览">
        <div class="preview-window"><header><img :src="safeLogo" alt="" /><div><strong>{{ form.platformName || '平台名称' }}</strong><small>{{ form.organizationName || '公司或医院' }}</small></div></header><main><span>实时预览</span><h2>{{ form.platformName || '平台名称' }}</h2><p>平台内容会显示在这里，并使用所选页面背景色。</p></main><footer>{{ form.ownershipText || '所有权文字' }}</footer></div>
      </section>
    </div>
  </div>
</template>
<script setup>
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { platformBranding, applyPlatformBranding } from '@/utils/platformBranding';
import { savePlatformBranding } from '@/api/platformBranding';
const form = reactive({ platformName: platformBranding.platformName, organizationName: platformBranding.organizationName, logo: platformBranding.logo, pageBackground: platformBranding.pageBackground, ownershipText: platformBranding.ownershipText });
const saving = ref(false);
const validColor = computed(() => /^#[0-9a-f]{6}$/i.test(form.pageBackground) ? form.pageBackground : '#f5f7fb');
const safeLogo = computed(() => /^(\/|https:\/\/|data:image\/(png|jpeg|webp|svg\+xml);base64,)/i.test(form.logo || '') ? form.logo : '/logo.svg');
function selectLogo(file) { if (!file.raw || file.size > 1024 * 1024) { ElMessage.error('请选择不超过 1 MB 的图片。'); return; } const reader = new FileReader(); reader.onload = () => { form.logo = String(reader.result); }; reader.readAsDataURL(file.raw); }
async function save() { if (!form.platformName.trim() || !form.ownershipText.trim() || !/^#[0-9a-f]{6}$/i.test(form.pageBackground)) { ElMessage.error('请填写必填项，并使用六位十六进制背景色。'); return; } saving.value = true; try { const result = await savePlatformBranding(form); applyPlatformBranding(result.data); ElMessage.success('平台品牌已保存并应用。'); } finally { saving.value = false; } }
</script>
<style scoped>
.branding-page{max-width:1320px;margin:0 auto;padding:30px 32px 54px}.branding-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:24px;margin-bottom:22px}.branding-heading span{font-size:11px;font-weight:700;letter-spacing:.14em;color:#387c70}.branding-heading h1{margin:7px 0;font-size:30px}.branding-heading p{margin:0;color:#6c7e79}.branding-layout{display:grid;grid-template-columns:minmax(360px,.9fr) minmax(420px,1.1fr);gap:22px}.branding-form-card{border-radius:16px}.logo-editor{display:flex;align-items:center;gap:16px;margin-bottom:12px}.logo-editor img{width:64px;height:64px;object-fit:contain;padding:7px;border:1px solid #dfe8e5;border-radius:14px;background:#fff}.logo-editor small{display:block;margin-top:7px;color:#7c8e89}.color-editor{display:flex;align-items:center;gap:10px;width:230px}.branding-preview{min-height:560px;padding:32px;border:1px solid #dce7e3;border-radius:18px;transition:background-color .2s}.preview-window{height:100%;min-height:496px;display:flex;flex-direction:column;border:1px solid #dae4e1;border-radius:15px;background:#fff;box-shadow:0 20px 50px rgb(32 63 55 / 10%);overflow:hidden}.preview-window header{display:flex;align-items:center;gap:12px;padding:18px 22px;border-bottom:1px solid #e7eeeb}.preview-window header img{width:40px;height:40px;object-fit:contain}.preview-window header strong,.preview-window header small{display:block}.preview-window header small{color:#82918d}.preview-window main{flex:1;padding:60px 44px}.preview-window main span{font-size:10px;letter-spacing:.15em;color:#397d70}.preview-window main h2{font-size:28px;margin:10px 0}.preview-window main p{color:#73847f}.preview-window footer{padding:18px;text-align:center;color:#7b8985;font-size:12px;border-top:1px solid #e7eeeb}@media(max-width:900px){.branding-page{padding:20px 16px 100px}.branding-heading{align-items:stretch;flex-direction:column}.branding-layout{grid-template-columns:1fr}.branding-preview{min-height:430px;padding:15px}.preview-window{min-height:400px}}
</style>
