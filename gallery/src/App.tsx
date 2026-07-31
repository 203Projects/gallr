import { useMemo } from "react";
import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseOwnerAuth } from "./auth/SupabaseOwnerAuth";
import { OwnerApp } from "./components/OwnerApp";
import { SupabaseOwnerRepository } from "./data/SupabaseOwnerRepository";
import { supabase } from "./lib/supabase";
import "./styles.css";

export function GalleryRoot({ client }: { client: SupabaseClient | null }) {
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

  return <OwnerApp auth={dependencies.auth} repository={dependencies.repository} />;
}

export default function App() {
  return <GalleryRoot client={supabase} />;
}
