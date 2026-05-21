# BidMart Architecture

In this document, we discuss the architecture of our project, BidMart.
We implement BidMart as a microservice-based marketplace platform. The system
separates concerns into multiple services so each business capability can evolve
independently while still participating in the same end-to-end auction and order
flow.

At the moment, the main containers in our system are:
- `bidmart-frontend` as the React client used by buyers and sellers.
- `bidmart-infrastructure` as the Spring Cloud Gateway entry point and Docker
  Compose deployment configuration.
- `bidmart-auth-service` for authentication, authorization, roles, sessions,
  OAuth login, and email verification.
- `bidmart-catalogue-service` for listings, categories, and catalogue search.
- `bidmart-auction-service-rust` for auction lifecycle, bidding, and auction
  closure.
- `bidmart-wallet-service-rust` for wallet balance, fund holds, top-up, and
  payment integration.
- `bidmart-order-and-notification-service` for order creation, order state, and
  user notifications.

Our current platform also depends on infrastructure services. Each business
service owns its own PostgreSQL database, Redis is used by the authentication
flow for session-related caching, and RabbitMQ is used for asynchronous events
between services such as auction updates, wallet provisioning, and order
creation. The entire local environment is deployed with Docker Compose.

## Current Context Diagram

In the context diagram, we show BidMart as a single system from the perspective
of its external users and third-party integrations. Buyers and sellers interact
with one marketplace platform, while the system itself communicates with Google
OAuth for identity verification, an email provider for verification messages,
and Midtrans for payment-related workflows.

![Current Context Diagram](assets/1.%20Current%20Context%20Diagram.png)

## Current Container Diagram

In the container diagram, we expand the system boundary and show how BidMart is
internally divided. The frontend sends requests to the API Gateway, and the
gateway routes them to the appropriate backend service. This design helps keep
authentication, catalogue management, auctions, wallet operations, and
order/notification concerns separated. It also makes integration clearer,
because service-to-service collaboration can happen through direct HTTP calls or
through events published to RabbitMQ.

In our current architecture, each service is responsible for its own data and
business rules. This reduces coupling at the code level, but it also means the
overall platform depends on coordination across multiple services and messaging
infrastructure.

![Current Container Diagram](assets/2.%20Current%20Container%20Diagram.png)

## Current Deployment Diagram

In the deployment diagram, we show how the system currently runs in development. The
frontend, gateway, backend services, PostgreSQL instances, RabbitMQ, and Redis
are deployed as separate containers inside one Docker Compose network. External
providers such as Google OAuth, SMTP, and Midtrans are outside the local
environment but are still part of the operational architecture because some
runtime flows depend on them.

This deployment style is practical for our local integration testing because it
keeps all major platform components available in one reproducible environment.
At the same time, it highlights our current architectural constraint that the
system is still strongly tied to a single-node local deployment model.

![Current Deployment Diagram](assets/3.%20Current%20Deployment%20Diagram.png)

## Future Architecture

In our future architecture, we keep the same business service boundaries, but
we change the runtime model so BidMart can survive growth and failure scenarios
more gracefully. The main architectural shift is to move away from a
single-instance development-style deployment and toward a production shape with
replication, better event recovery, managed storage, and platform-level
observability.

We derived these future diagrams from the risks we discovered during our
discussion. High-traffic catalogue reads, bidding spikes near auction
deadlines, payment settlement uncertainty, and weak operational visibility are
our most important concerns. The future design therefore adds horizontal
scaling, a more robust eventing setup, a search read model, object storage with
CDN delivery, and better incident detection across the platform.

## Future Context Diagram

The future context diagram still centers on buyers and sellers, but it adds the
supporting operational capabilities needed for a successful marketplace.
We treat platform operations as a first-class concern because growth means we
must actively monitor health, incidents, and recovery workflows instead of only
running the system locally.

![Future Context Diagram](assets/4.%20Future%20Context%20Diagram.png)

## Future Container Diagram

In the future container diagram, we show the main structural improvements. A CDN and
load balancer sit in front of the frontend and gateway, services can scale
horizontally, and supporting platform services such as search, object storage,
observability, and secrets management are introduced to reduce pressure on the
transactional path.

![Future Container Diagram](assets/5.%20Future%20Container%20Diagram.png)

## Future Deployment Diagram

In the future deployment diagram, we make the production intent explicit. Instead of
one local node, traffic is distributed across replicated gateway and service
instances, persistent messaging is treated as clustered infrastructure, and the
data platform includes backup, monitoring, and recovery-oriented capabilities.

![Future Deployment Diagram](assets/6.%20Future%20Deployment%20Diagram.png)

## Risk Assessment Tables

Before the detailed risk storming discussion, we assessed the future
architecture using a simple impact-and-likelihood matrix and then mapped the
resulting scores onto the most important BidMart workflows.

### Risk Matrix

| Impact \\ Likelihood | Low (1) | Medium (2) | High (3) |
| --- | --- | --- | --- |
| Low impact (1) | 1 | 2 | 3 |
| Medium impact (2) | 2 | 4 | 6 |
| High impact (3) | 3 | 6 | 9 |

### Risk Assessment

| Risk criteria | Customer registration and login | Catalogue browse and search | Auction bidding and close | Payment and order completion | Total risk |
| --- | --- | --- | --- | --- | --- |
| Scalability | 2 | 6 | 6 | 2 | 16 |
| Availability | 3 | 4 | 6 | 4 | 17 |
| Performance | 2 | 6 | 4 | 2 | 14 |
| Security | 6 | 2 | 2 | 6 | 16 |
| Data integrity | 3 | 3 | 9 | 9 | 24 |
| Total risk | 16 | 21 | 27 | 23 | 87 |

### Filtering High Risk

We treated only scores `>= 6` as high-risk items that required direct
architectural mitigation.

| Risk criteria | Customer registration and login | Catalogue browse and search | Auction bidding and close | Payment and order completion | Total high risk |
| --- | --- | --- | --- | --- | --- |
| Scalability | - | 6 | 6 | - | 12 |
| Availability | - | - | 6 | - | 6 |
| Performance | - | 6 | - | - | 6 |
| Security | 6 | - | - | 6 | 12 |
| Data integrity | - | - | 9 | 9 | 18 |
| Total high risk | 6 | 12 | 21 | 15 | 54 |

### Risk Direction

In the table below, we show the current score and the expected residual score
after the future architecture improvements are applied.

| Risk criteria | Customer registration and login | Catalogue browse and search | Auction bidding and close | Payment and order completion | Total direction |
| --- | --- | --- | --- | --- | --- |
| Scalability | 2 -> 2 | 6 -> 3 | 6 -> 4 | 2 -> 2 | 16 -> 11 |
| Availability | 3 -> 2 | 4 -> 3 | 6 -> 3 | 4 -> 3 | 17 -> 11 |
| Performance | 2 -> 2 | 6 -> 3 | 4 -> 3 | 2 -> 2 | 14 -> 10 |
| Security | 6 -> 3 | 2 -> 2 | 2 -> 2 | 6 -> 4 | 16 -> 11 |
| Data integrity | 3 -> 2 | 3 -> 2 | 9 -> 4 | 9 -> 4 | 24 -> 12 |
| Total risk | 16 -> 11 | 21 -> 13 | 27 -> 16 | 23 -> 15 | 87 -> 55 |

## Risk Storming Explanation

We applied risk storming to BidMart because the system is already
distributed and several business-critical workflows cross service boundaries.
Authentication relies on OAuth and email verification, auctions depend on
timing and consistency, wallet operations depend on third-party payment
behavior, and orders plus notifications depend on asynchronous event delivery.
Once we assume the project becomes successful and traffic grows, the
architecture must be evaluated not only for correctness, but also for how it
behaves under load, delay, duplication, and partial service failure.

In our discussion, we focused on the most important end-to-end
scenarios: catalogue browsing, bidding near the auction deadline, auction
closing, wallet settlement, and order notification delivery. Those scenarios
were mapped to our current architecture to identify hot spots. The largest
risks were gateway and service single points of failure, catalogue search load
on the transactional database, lost or duplicated RabbitMQ events, payment
callback uncertainty, and weak operational visibility when something goes
wrong. This made risk storming useful because it connected technical failure
points directly to user-facing marketplace flows.

We justified the future architecture because each modification directly reduces
one or more of those risks. Replication and ingress reduce availability
bottlenecks, durable messaging with retries and dead-letter queues makes event
failures recoverable, a search index and CDN reduce load on transactional
services, and observability plus payment reconciliation make incidents easier to
detect and resolve. The goal is not to redesign BidMart from scratch, but to
make the existing service boundaries reliable enough for success.

### Risk Summary

| Risk area | Current concern | Future mitigation |
| --- | --- | --- |
| Availability | One gateway and one service instance can become a bottleneck or failure point. | Load balancer and service replicas. |
| Event consistency | Auction and order workflows can suffer from lost or duplicated events. | Durable RabbitMQ, retry queues, DLQ, and idempotent consumers. |
| Catalogue scalability | Search traffic can overload the transactional catalogue database. | Search index for read-heavy discovery. |
| Media delivery | Listing images should not stay on the transactional application path. | Object storage with CDN delivery. |
| Payment reliability | Midtrans callbacks and settlement state can become unclear during failure. | Payment reconciliation and explicit payment-state handling. |
| Diagnosis | Failures are hard to understand from container logs alone. | Shared observability with logs, metrics, traces, and alerts. |

### Risk Analysis

This diagram summarizes the main scenarios, architectural risks, and the
modifications chosen to address them.

![Risk Analysis](assets/7.%20Risk%20Analysis.png)

### Risk Matrix Diagram

The image below shows the same impact-likelihood scoring model in visual form.

![Risk Matrix](assets/8.%20Risk%20Matrix.png)

### Risk Storming Overview

This overview shows the structure of the risk storming activity: identify the
risk areas, build consensus on what matters most, and then focus mitigation on
the highest-value changes.

![Risk Storming Overview](assets/9.%20Risk%20Storming%20Overview.png)

### Risk Storming: Identification

In the identification step, we marked the components and interactions
most exposed to scale, availability, consistency, and observability problems.

![Risk Storming Identification](assets/10.%20Risk%20Storming%20Identification.png)

### Risk Storming: Consensus

After identification, we aligned on the final high-priority hotspots that
deserved immediate architectural attention.

![Risk Storming Consensus](assets/11.%20Risk%20Storming%20Consensus.png)

### Risk Storming: Mitigation

The mitigation step connected those agreed risks to concrete architectural
changes, so the future architecture was grounded in operational problems rather
than generic improvement ideas.

![Risk Storming Mitigation](assets/12.%20Risk%20Storming%20Mitigation.png)

### Architecture Modification Justification

The diagram below summarizes why the selected changes were chosen and how they
improve resilience, consistency, scalability, and diagnosis across BidMart.

![Architecture Modification Justification](assets/13.%20Architecture%20Modification%20Justifciation.png)

## Individual Work: Auction Service

For my individual work, I focus on `bidmart-auction-service-rust`. This module
owns auction creation, bid placement, auction closure, bid validation, wallet
hold coordination, listing validation, and auction domain event publication.
Because I am limited to four screenshots, I use one combined structural diagram
and three code-flow diagrams that capture the most important auction behaviors.

### Auction Service Overview

In this overview, I combine the individual container view and the component
view into one diagram. It shows how the auction service sits behind the API
Gateway, how it depends on the catalogue and wallet services, how it persists
state in PostgreSQL, and how its internal router, service, domain, repository,
closure scheduler, and outbox publisher work together.

![Auction Service Overview](assets/14.%20Auction%20Components.png)

### Code Diagram 1: Create Auction Flow

This code diagram shows how a seller request becomes a persisted auction. The
important steps are request validation, listing ownership and status
verification, and writing the initial auction aggregate into the auction data
store.

![Auction Code Create Auction Flow](assets/15.%20Auction%20Code%20Create%20Auction%20Flow.png)

### Code Diagram 2: Place Bid Flow

This code diagram captures the most critical runtime path in the auction
service. It shows concurrency control, listing validation, domain rule
evaluation, wallet hold creation, previous-hold release, and the insertion of a
`BidPlaced` outbox event for downstream consumers.

![Auction Code Place Bid Flow](assets/16.%20Auction%20Code%20Place%20Bid%20Flow.png)

### Code Diagram 3: Close Auction Flow

This code diagram explains how expired auctions are closed. It highlights the
interaction between the closure scheduler, outcome determination, wallet
settlement or hold release, and the outbox relay that publishes
`auction.ended.v1` to RabbitMQ.

![Auction Code Close Auction Flow](assets/17.%20Auction%20Code%20Close%20Auction%20Flow.png)

## Individual Work: Catalogue Service

1. Component Diagram
The Catalogue Service utilizes a layered architecture. Incoming requests from the API Gateway pass through the Auth Interceptor for token validation before reaching the Controllers. The Controllers delegate business logic to the Service layer, which then interacts with the Repositories to perform read/write operations on the PostgreSQL database. Additionally, the service implements asynchronous communication via RabbitMQ. The Listing Event Publisher broadcasts events (like ListingCreated), while the Auction Event Consumer listens for external events (like BidPlaced from the Auction Service) to update listing statuses automatically.

2. Code Diagram
The codebase follows the standard Spring Boot MVC pattern. It revolves around two main entities, Listing and Category, connected by a Many-to-One relationship. To maintain loose coupling and high testability, the business logic is abstracted using interfaces (ListingService and CategoryService) that are realized by concrete implementation classes. This Service layer acts as the central coordinator, managing database transactions and interacting with the RabbitMQ publisher/consumer classes to keep the system state synchronized.

![catalogue component](<assets/18. bidmart-architecture-catalogue component.png>)
![catalogue domain model](<assets/19. bidmart-architecture-Code 1 CatalogueDomain Model.png>)

## Individual Work: Wallet Service

### Wallet Component

![wallet component](./assets/20.%20wallet-component-diagram.png)

1. This diagram illustrates the internal architecture and separation of concerns within the Wallet Service container. It demonstrates a clean architectural flow where external REST API calls are first handled by the HTTP Router layer, which validates inputs before passing them to the core Wallet and Reconciliation services. These core services act as orchestrators that execute the primary business use cases, communicating with external gateways like Midtrans and relying on the Persistence layer to handle all PostgreSQL database operations, ensuring that business logic remains entirely decoupled from routing and data storage mechanisms.

### Wallet Domain

![wallet domain](./assets/21.%20wallet-domain.png)

2. This class diagram highlights the structural boundary between the application's pure business logic and its database representation. On the left, the Domain Layer encapsulates core entities like Wallet and the Money value object, enforcing strict financial rules (such as validating sufficient balances during a withdraw or hold action) entirely independent of any database framework. On the right, the Persistence Layer defines raw data structures like WalletRow that perfectly mirror the SQL tables, using explicit mapping implementations to safely translate these raw database records into the validated domain entities used by the system.
