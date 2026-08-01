import { useMemo } from "react";
import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseOwnerAuth } from "./auth/SupabaseOwnerAuth";
import { OwnerApp } from "./components/OwnerApp";
import { SupabaseOwnerRepository } from "./data/SupabaseOwnerRepository";
import { supabase } from "./lib/supabase";
import "./styles.css";

const configuredPublicSiteUrl = import.meta.env.VITE_PUBLIC_SITE_URL || "https://gallrmap.com";
const configuredLaunchKitEnabled = import.meta.env.VITE_LAUNCH_KIT_ENABLED === "true";

export function GalleryRoot({
  client,
  publicSiteUrl = configuredPublicSiteUrl,
  launchKitEnabled = configuredLaunchKitEnabled,
}: {
  client: SupabaseClient | null;
  publicSiteUrl?: string;
  launchKitEnabled?: boolean;
}) {
  const dependencies = useMemo(() => client ? {
    auth: new SupabaseOwnerAuth(client),
    repository: new SupabaseOwnerRepository(client),
  } : null, [client]);

  if (!dependencies) {
    return (
      <main className="blocked-layout">
        <strong>gallr gallery</strong>
        <section>
          <h1>Configuration required</h1>
          <p>Gallery workspace configuration is missing.</p>
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

export default function App() {
  return <GalleryRoot client={supabase} />;
}
