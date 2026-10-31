<h1 align="center">🍒🍓🍎🥑♻️ StockRescue AI ♻️🥑🍎🍓🍒</h1>

**Turn surplus into opportunity.**

StockRescue AI is a project in development that aims to help small retail chains manage inventory more effectively and reduce avoidable food waste.

A product may be approaching its expiry date in one store while another location could still sell it. StockRescue is being built to help teams spot situations like this, understand their options, and make better decisions before stock goes to waste.

## What we're building

The application will bring inventory management and decision support into one place. Store teams will be able to track products and batches, review stock across locations, import their own sales and inventory data, and manage stock movements.

Alongside everyday inventory operations, StockRescue will use demand forecasting to identify products at risk of remaining unsold. It will then help managers compare possible actions, starting with transfers between stores.

For example, if one store has more of a product than it is likely to sell, the system could suggest moving some of that stock to a nearby location with higher expected demand. The manager would see the reasoning, costs, and relevant restrictions before deciding whether to proceed. Approved transfers would be handled and tracked within the application.

The aim is to make recommendations useful in day-to-day operations, not just display predictions on a dashboard.

## Technology

The working foundation uses:

- **Java and Spring Boot** for inventory management, business workflows, APIs, and security.
- **PostgreSQL** for operational data and inventory history.
- **Python and FastAPI** for demand forecasting and optimization.
- **React and TypeScript** for the web application.
- **Docker Compose** for local development.

## Run locally

With Docker Engine/Desktop and Docker Compose available, run:

```sh
docker compose --env-file .env.example up --build --wait
```

Open <http://localhost:8080>. This starts the web shell, Java backend,
PostgreSQL, Kafka and private Python service. The example credentials are for
local development only. Stop with `docker compose --env-file .env.example down`;
the PostgreSQL development volume is preserved.

Use **Sign in** to register an account, then sign in with its email and password.
Registration gives no organization or store access. The local Compose profile
uses HTTP cookies for development; other profiles require Secure cookies and
an HTTPS origin. Account/session data persists in PostgreSQL across restarts.
From your account, create your first organization to become its initial Org Admin.
Choose an organization to open its workspace; access is checked against current
membership on every request. This onboarding can be completed once per account.
Org Admins can open **Manage members** to create a private invitation link,
review the recipient's account, and manage roles or store grants. Links are
shared manually; no email is sent. Claiming a link alone does not activate access.
Use **Open stores** to create stores with a code, name and explicit IANA timezone.
Codes stay reserved within the organization. Administrators can edit/deactivate
stores; Store Managers can edit assigned stores. Deactivation retains history.
Open a store's **Storage zones** to declare its temperature classes. Org Admins
can configure **Transport lanes** between compatible zones, approve decimal
costs with a currency and journey duration, and review previous or future prices.
Missing configuration is explicit; configured routes do not prove capacity.
The workspace navigation connects stores, access, transport and **Settings**.
Switch organizations from the selector and sign out from any workspace page.
Your account reference is available on the account page for invitation review.

## Project status

SR-001 provides the runnable technical foundation: migrations, service health,
Java-to-Python communication, an explicit Kafka producer/consumer smoke check,
and a responsive web shell showing real backend readiness. Account registration,
sign-in/sign-out, persisted sessions, CSRF protection, organization onboarding and
membership-validated organization selection, reviewed invitations and audited
access management, store creation/editing/deactivation, storage zones and
transport lanes with immutable cost revisions are implemented.
Inventory, transfers and forecasting are still being built.

See the [architecture notes](documents/architecture.md) for service boundaries
and [contributor instructions](CONTRIBUTING.md) for development checks.

Questions and suggestions are welcome through [GitHub Issues](https://github.com/MiloszPodsiadly/StockRescue/issues).

