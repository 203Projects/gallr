import { useMemo } from "react";
import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseOwnerAuth } from "./auth/SupabaseOwnerAuth";
import { OwnerApp } from "./components/OwnerApp";
import { SupabaseOwnerRepository } from "./data/SupabaseOwnerRepository";
import { supabase } from "./lib/supabase";
import { LocaleProvider, LocaleToggle, useLocale } from "./i18n";
import "./styles.css";

const configuredPublicSiteUrl = import.meta.env.VITE_PUBLIC_SITE_URL || "https://gallrmap.com";
const configuredLaunchKitEnabled = import.meta.env.VITE_LAUNCH_KIT_ENABLED === "true";

function GalleryRootContent({
  client,
  publicSiteUrl = configuredPublicSiteUrl,
  launchKitEnabled = configuredLaunchKitEnabled,
}: {
  client: SupabaseClient | null;
  publicSiteUrl?: string;
  launchKitEnabled?: boolean;
}) {
  const { messages } = useLocale();
  const dependencies = useMemo(() => client ? {
    auth: new SupabaseOwnerAuth(client),
    repository: new SupabaseOwnerRepository(client),
  } : null, [client]);

  if (!dependencies) {
    return (
      <main className="blocked-layout">
        <strong>{messages.common.brand}</strong>
        <section>
          <LocaleToggle className="standalone-locale-toggle" />
          <h1>{messages.common.configurationRequired}</h1>
          <p>{messages.common.configurationMissing}</p>
        </section>
      </main>
    );
  }

  return (
    <OwnerApp
      auth={dependencies.auth}
      repository={dependencies.repository}
      publicSiteUrl={publicSiteUrl}
      launchKitEnabled={launchKitEnabled}
    />
  );
}

export function GalleryRoot(props: {
  client: SupabaseClient | null;
  publicSiteUrl?: string;
  launchKitEnabled?: boolean;
}) {
  return (
    <LocaleProvider>
      <GalleryRootContent {...props} />
    </LocaleProvider>
  );
}

export default function App() {
  return <GalleryRoot client={supabase} />;
}
