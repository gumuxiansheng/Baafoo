<template>
  <img
    class="baafoo-logo"
    :src="logoSrc"
    alt="Baafoo"
  />
</template>

<script>
import logoSvg from '@/assets/baafoo-logo.svg'

export default {
  name: 'BaafooLogo',
  props: {
    variant: {
      type: String,
      default: 'default',
    },
  },
  computed: {
    logoSrc() {
      return logoSvg
    },
  },
}
</script>

<style scoped>
.baafoo-logo {
  display: inline-block;
  vertical-align: middle;
}
</style>
