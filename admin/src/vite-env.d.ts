/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SUPABASE_URL?: string;
  readonly VITE_SUPABASE_PUBLISHABLE_KEY?: string;
  readonly VITE_NAVER_MAPS_CLIENT_ID?: string;
  readonly VITE_ADMIN_FIXTURE_MODE?: string;
  readonly VITE_ADMIN_PROMOTIONS_ENABLED?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
