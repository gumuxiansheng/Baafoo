<template>
  <svg class="baafoo-logo" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
    <circle cx="32" cy="32" r="30" fill="currentColor" opacity="0.12"/>
    <circle cx="32" cy="34" r="15" fill="var(--bf-surface)"/>
    <path d="M24 25 Q21 14 17 10" stroke="currentColor" stroke-width="3" stroke-linecap="round" fill="none"/>
    <path d="M40 25 Q43 14 47 10" stroke="currentColor" stroke-width="3" stroke-linecap="round" fill="none"/>
    <circle cx="26" cy="32" r="3" fill="var(--bf-text)"/>
    <circle cx="38" cy="32" r="3" fill="var(--bf-text)"/>
  </svg>
</template>

<script>
export default {
  name: 'BaafooLogo',
}
</script>

<style scoped>
.baafoo-logo {
  display: inline-block;
  vertical-align: middle;
  width: 32px;
  height: 32px;
}
</style>
