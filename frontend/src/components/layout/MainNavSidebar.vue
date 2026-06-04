<template>
  <aside class="main-nav-sidebar">
    <div class="main-nav-brand">
      <div class="brand-mark">R</div>
      <div>
        <strong>Ricbot</strong>
        <span>Agent Console</span>
      </div>
    </div>
    <nav class="main-nav-links" aria-label="Console navigation">
      <a
        v-for="route in routes"
        :key="route.path"
        :href="route.path"
        :class="{ active: currentRoute.path === route.path }"
        @click.prevent="navigate(route.path)"
      >
        {{ route.label }}
      </a>
    </nav>
  </aside>
</template>

<script setup lang="ts">
import { currentRoute, navigate, routes } from '@/router';
</script>
