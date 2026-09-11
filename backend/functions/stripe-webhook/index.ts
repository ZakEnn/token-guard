// POST /v1/stripe-webhook (plan §5.3): Stripe subscription lifecycle ->
// license status transitions. Verifies the Stripe signature; service role.
import Stripe from "https://esm.sh/stripe@14?target=deno";
import { json, serviceClient } from "../_shared/util.ts";

const stripe = new Stripe(Deno.env.get("STRIPE_SECRET_KEY")!, { apiVersion: "2023-10-16" });
const WEBHOOK_SECRET = Deno.env.get("STRIPE_WEBHOOK_SECRET")!;

// Stripe subscription.status -> our license.status
const STATUS_MAP: Record<string, string> = {
  trialing: "trialing", active: "active", past_due: "past_due",
  canceled: "expired", unpaid: "expired", incomplete_expired: "expired",
};

Deno.serve(async (req) => {
  const sig = req.headers.get("stripe-signature");
  if (!sig) return json({ error: "no signature" }, 400);
  const raw = await req.text();
  let event: Stripe.Event;
  try {
    event = await stripe.webhooks.constructEventAsync(raw, sig, WEBHOOK_SECRET);
  } catch (e) {
    return json({ error: `signature: ${e.message}` }, 400);
  }

  const db = serviceClient();
  if (event.type.startsWith("customer.subscription.")) {
    const sub = event.data.object as Stripe.Subscription;
    const status = STATUS_MAP[sub.status] ?? "expired";
    const tier = (sub.items.data[0]?.price.metadata?.tier) ?? "personal";
    const seats = Number(sub.items.data[0]?.quantity ?? 1);
    await db.from("licenses").update({
      status, tier, seat_limit: seats, stripe_subscription_id: sub.id,
    }).eq("stripe_subscription_id", sub.id);
  }
  return json({ received: true });
});
