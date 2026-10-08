import { redirect } from "next/navigation";
import { hasSession } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string }>;
}) {
  if (await hasSession()) redirect("/");
  const { error } = await searchParams;
  return (
    <div className="login">
      <form className="login-card" action="/api/session" method="post">
        <h1 className="title">FatihsMG4</h1>
        <p className="subtitle">Tüketim ve şarj geçmişi</p>
        {/*
          A hidden username so password managers will offer to save this.
          Most of them ignore a lone password field — they key a saved entry on
          (origin, username), and with nothing to key on they stay quiet. There is only
          ever one account here, so the value is fixed and the server never reads it.
        */}
        <input
          type="text"
          name="username"
          value="fatih"
          autoComplete="username"
          readOnly
          hidden
        />
        <input
          className="field"
          type="password"
          name="password"
          placeholder="Parola"
          autoComplete="current-password"
          autoFocus
          required
        />
        <button className="button" type="submit">
          Giriş
        </button>
        {error && <p className="error">Parola hatalı.</p>}
      </form>
    </div>
  );
}
