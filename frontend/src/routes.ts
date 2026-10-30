import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router'
import { RootLayout } from './root-layout'
import { MissingPage, RouteFailure } from './components/route-failure'
import { App } from './App'
import { SignInPage, SignUpPage } from './features/auth/auth-page'
import { AccountPage } from './features/auth/account-page'
import { MembersPage } from './features/access/members-page'
import { AcceptInvitationPage } from './features/access/accept-invitation-page'
import { StoresPage } from './features/stores/stores-page'
import { StorePage } from './features/stores/store-page'
import { ZonesPage } from './features/configuration/zones-page'
import { LanePage, LanesPage } from './features/configuration/lanes-page'
import { SettingsPage } from './features/organizations/settings-page'

const root = createRootRoute({ component: RootLayout, notFoundComponent: MissingPage, errorComponent: RouteFailure })
const home = createRoute({ getParentRoute: () => root, path: '/', component: App })
const signIn = createRoute({ getParentRoute: () => root, path: '/sign-in', component: SignInPage })
const signUp = createRoute({ getParentRoute: () => root, path: '/sign-up', component: SignUpPage })
const account = createRoute({ getParentRoute: () => root, path: '/account', component: AccountPage })
const members = createRoute({ getParentRoute: () => root, path: '/organizations/$organizationId/members', component: MembersPage })
const acceptInvitation = createRoute({ getParentRoute: () => root, path: '/invitations/accept', component: AcceptInvitationPage })
const stores = createRoute({ getParentRoute: () => root, path: '/organizations/$organizationId/stores', component: StoresPage })
const store = createRoute({ getParentRoute: () => root, path: '/organizations/$organizationId/stores/$storeId', component: StorePage })
const zones = createRoute({ getParentRoute: () => root, path: '/organizations/$organizationId/stores/$storeId/storage-zones', component: ZonesPage })
const lanes = createRoute({ getParentRoute: () => root, path: '/organizations/$organizationId/transport-lanes', component: LanesPage })
const lane = createRoute({ getParentRoute: () => root, path: '/organizations/$organizationId/transport-lanes/$laneId', component: LanePage })
const settings = createRoute({ getParentRoute: () => root, path: '/organizations/$organizationId/settings', component: SettingsPage })

export const router = createRouter({ routeTree: root.addChildren([home, signIn, signUp, account, members, acceptInvitation, stores, store, zones, lanes, lane, settings]) })
