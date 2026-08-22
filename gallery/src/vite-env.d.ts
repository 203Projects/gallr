/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SUPABASE_URL?: string;
  readonly VITE_SUPABASE_PUBLISHABLE_KEY?: string;
  readonly VITE_PUBLIC_SITE_URL?: string;
  readonly VITE_LAUNCH_KIT_ENABLED?: string;
  readonly VITE_OWNER_PROMOTION_ENABLED?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
