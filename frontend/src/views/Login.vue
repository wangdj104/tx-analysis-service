<template>
  <main class="login-page">
    <header class="login-brand">
      <img src="/logo.svg?v=5" alt="" class="brand-logo" />
      <div class="brand-copy">
        <span class="brand-name">Chengxin Health</span>
        <span class="brand-description">Personal Health Management Platform</span>
      </div>
    </header>

    <div class="login-layout">
      <section class="login-intro" aria-labelledby="intro-title">
        <p class="intro-eyebrow"><span></span> Make every health record meaningful</p>
        <h1 id="intro-title">Track with care,<br />manage your health with confidence.</h1>
        <p class="intro-description">Bring daily readings and medical records together,<br />so changes are clear and every day feels more secure.</p>

        <picture class="health-illustration">
          <source media="(max-width: 800px)" :srcset="careMomentsSmall" />
          <img
            :src="careMoments"
            alt=""
            width="440"
            height="280"
            loading="eager"
            decoding="async"
          />
        </picture>
        <p class="intro-footer">Medical Records <span>·</span> Daily Monitoring <span>·</span> Trend Analysis</p>
      </section>

      <section class="login-card" aria-labelledby="login-title">
        <div class="login-heading">
          <div class="login-welcome">
            <p class="welcome-label">Welcome back</p>
            <img
              :src="careMomentsSmall"
              alt=""
              class="mobile-care-illustration"
              width="180"
              height="112"
              loading="lazy"
              decoding="async"
            />
          </div>
          <h2 id="login-title">Sign in to Chengxin Health</h2>
          <p class="login-subtitle">Keep tracking and understand how your health changes.</p>
        </div>

        <el-form
          ref="loginFormRef"
          :model="loginForm"
          :rules="loginRules"
          class="login-form"
          label-position="top"
          @submit.prevent="handleLogin"
        >
          <el-form-item prop="username" label="Username">
            <el-input
              v-model="loginForm.username"
              name="username"
              autocomplete="username"
              placeholder="Enter your username"
              size="large"
              :prefix-icon="User"
            />
          </el-form-item>

          <el-form-item prop="password" label="Password">
            <el-input
              v-model="loginForm.password"
              name="password"
              autocomplete="current-password"
              type="password"
              placeholder="Enter your password"
              size="large"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>

          <el-button
            type="primary"
            native-type="submit"
            size="large"
            :loading="loading"
            class="login-button"
          >
            {{ loading ? 'Signing in…' : 'Sign In' }}
            <el-icon v-if="!loading"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <p class="login-note"><el-icon><Lock /></el-icon>Sign in with an active account</p>
      </section>
    </div>

    <footer class="page-footer">Chengxin Health <span>Connected care for every family</span></footer>
  </main>
</template>

<script setup>
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { User, Lock, ArrowRight } from '@element-plus/icons-vue';
import { login, saveAuthSession, clearAuthSession } from '@/api/auth';
import careMoments from '@/assets/illustrations/care-moments.webp';
import careMomentsSmall from '@/assets/illustrations/care-moments-small.webp';

const router = useRouter();
const loginFormRef = ref(null);
const loading = ref(false);

const loginForm = reactive({
  username: '',
  password: ''
});

const loginRules = {
  username: [
    { required: true, message: 'Enter your username', trigger: 'blur' }
  ],
  password: [
    { required: true, message: 'Enter your password', trigger: 'blur' },
    { min: 6, message: 'Password must be at least 6 characters', trigger: 'blur' }
  ]
};

const handleLogin = async () => {
  if (!loginFormRef.value || loading.value) return;

  await loginFormRef.value.validate(async (valid) => {
    if (valid && !loading.value) {
      loading.value = true;
      try {
        clearAuthSession();
        const res = await login(loginForm);

        if (res.code === 200) {
          saveAuthSession(res.data);
          ElMessage.success('Signed in successfully');
          router.replace('/monitoring');
        } else {
          ElMessage.error(res.msg || 'Sign-in failed');
        }
      } catch (error) {
        ElMessage.error(error.message || 'Sign-in failed. Check your network connection.');
      } finally {
        loading.value = false;
      }
    }
  });
};
</script>

<style scoped>
.login-page {
  --login-green: #176b59;
  --login-ink: #233c34;
  --login-muted: #5f7066;
  --login-border: #788b7e;
  --login-danger: #b94747;
  box-sizing: border-box;
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  background: #f5f7f6;
  color: var(--login-ink);
  padding: 34px max(32px, calc((100vw - 1180px) / 2));
}

.login-brand { display: flex; align-items: center; gap: 11px; }
.brand-logo { width: 44px; height: 44px; }
.brand-copy { display: flex; flex-direction: column; gap: 3px; }
.brand-name { font-size: 19px; font-weight: 700; letter-spacing: 1px; }
.brand-description { color: var(--login-muted); font-size: 12px; letter-spacing: 1.5px; }

.login-layout {
  width: 100%;
  max-width: 1060px;
  margin: auto;
  padding: 58px 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  align-items: center;
  gap: 100px;
}

.intro-eyebrow { display: flex; align-items: center; gap: 9px; color: var(--login-green); font-size: 12px; font-weight: 500; letter-spacing: 1px; margin: 0 0 23px; }
.intro-eyebrow span { width: 6px; height: 6px; border-radius: 50%; background: #609d81; }
.login-intro h1 { margin: 0; font-size: clamp(34px, 3.5vw, 46px); line-height: 1.48; letter-spacing: -1px; font-weight: 650; }
.intro-description { margin: 20px 0 0; font-size: 14px; line-height: 1.9; color: var(--login-muted); }
.intro-footer { margin: 4px 0 0; color: var(--login-muted); font-size: 12px; letter-spacing: 1px; }
.intro-footer span { margin: 0 13px; }

.health-illustration {
  box-sizing: border-box;
  display: block;
  width: min(100%, 440px);
  height: 280px;
  margin: 24px 0 18px;
  border: 1px solid #e0e9df;
  border-radius: 30px 30px 64px 30px;
  background: radial-gradient(ellipse at 76% 22%, #faf5e7 0, transparent 56%), linear-gradient(140deg, #e5eee2, #f0f5eb);
  box-shadow: 0 12px 30px #44634b08;
}
.health-illustration img { box-sizing: border-box; display: block; width: 100%; height: 100%; padding: 4px 12px; object-fit: contain; }
.login-heading { position: relative; }
.mobile-care-illustration { display: none; }

.login-card {
  box-sizing: border-box;
  width: 100%;
  padding: 40px 36px 30px;
  background: #fff;
  border: 1px solid #e1e8e3;
  border-radius: 14px;
  box-shadow: 0 8px 36px #273e3206;
}

.welcome-label { color: var(--login-green); font-size: 12px; font-weight: 600; margin: 0 0 12px; }
.login-heading h2 { margin: 0; font-size: 26px; font-weight: 650; letter-spacing: -.5px; }
.login-subtitle { color: var(--login-muted); font-size: 13px; line-height: 1.7; margin: 10px 0 0; }
.login-form { margin-top: 32px; }
.login-form :deep(.el-form-item) { margin-bottom: 24px; }
.login-form :deep(.el-form-item__label) { padding: 0; margin-bottom: 8px; color: #344a40; font-weight: 500; font-size: 13px; line-height: 20px; }
.login-form :deep(.el-input__wrapper) { min-height: 46px; box-sizing: border-box; padding: 0 13px; border-radius: 8px; background: #fff; box-shadow: 0 0 0 1px var(--login-border) inset; transition: box-shadow .18s ease; }
.login-form :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px var(--login-muted) inset; }
.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px var(--login-green) inset; outline: 2px solid var(--login-green); outline-offset: 2px; }
.login-form :deep(.el-input__inner) { font-size: 14px; color: var(--login-ink); }
.login-form :deep(.el-input__inner::placeholder) { color: var(--login-muted); opacity: 1; }
.login-form :deep(.el-input__prefix) { color: var(--login-muted); margin-right: 3px; }
.login-form :deep(.el-input__password) { color: var(--login-muted); }
.login-form :deep(.el-form-item.is-error .el-input__wrapper) { box-shadow: 0 0 0 1px var(--login-danger) inset; }
.login-form :deep(.el-form-item__error) { padding-top: 5px; color: var(--login-danger); }
.login-form :deep(.el-form-item.is-required .el-form-item__label::before) { color: var(--login-danger); }

.login-button {
  --el-button-bg-color: #176b59;
  --el-button-border-color: #176b59;
  --el-button-hover-bg-color: #125a4a;
  --el-button-hover-border-color: #125a4a;
  --el-button-active-bg-color: #104e40;
  --el-button-active-border-color: #104e40;
  width: 100%;
  height: 46px;
  margin-top: 7px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
}

.login-button .el-icon { margin-left: 10px; }
.login-button:focus-visible { outline: 3px solid var(--login-green) !important; outline-offset: 3px; }
.login-note { display: flex; justify-content: center; align-items: center; gap: 6px; margin: 23px 0 0; color: var(--login-muted); font-size: 12px; }
.page-footer { display: flex; justify-content: center; gap: 16px; color: var(--login-muted); font-size: 12px; line-height: 1.6; }
.page-footer span { padding-left: 16px; border-left: 1px solid #d7e0d9; }

@media (max-width: 1050px) {
  .login-layout { gap: 48px; grid-template-columns: minmax(0, 1fr) 390px; }
  .login-card { padding: 36px 28px 28px; }
}

@media (max-width: 800px) {
  .login-page { padding: 26px 24px; }
  .login-layout { max-width: 420px; display: block; padding: 32px 0; }
  .login-intro { display: none; }
  .login-card { padding: 36px 30px 28px; }
  .login-welcome {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    height: 112px;
    margin: -36px -30px 24px;
    padding: 0 16px 0 24px;
    border-bottom: 1px solid #e3eadf;
    border-radius: 13px 13px 0 0;
    background: radial-gradient(ellipse at 90% 12%, #faf4e5, transparent 70%), #eaf1e5;
  }
  .login-welcome .welcome-label { flex-shrink: 0; margin: 0; font-size: 14px; letter-spacing: 1px; }
  .mobile-care-illustration { display: block; flex: 0 1 180px; min-width: 0; width: 180px; height: 112px; object-fit: contain; }
  .login-form { margin-top: 26px; }
  .brand-logo { width: 39px; height: 39px; }
  .brand-name { font-size: 18px; }
}

@media (max-width: 420px) {
  .login-page { padding: 22px 18px; }
  .login-card { padding: 30px 22px 25px; }
  .login-welcome { height: 106px; margin: -30px -22px 22px; padding-left: 20px; padding-right: 10px; }
  .mobile-care-illustration { height: 106px; }
  .login-heading h2 { font-size: 24px; }
  .page-footer { gap: 10px; }
  .page-footer span { padding-left: 10px; }
}
</style>
