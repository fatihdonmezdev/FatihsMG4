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
