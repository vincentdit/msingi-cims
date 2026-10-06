/// <reference types="vite/client" />
interface ImportMetaEnv {
  readonly VITE_TENANT?: string;
  readonly VITE_API_BASE?: string;
  readonly VITE_SHOW_DEV_TOOLS?: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
