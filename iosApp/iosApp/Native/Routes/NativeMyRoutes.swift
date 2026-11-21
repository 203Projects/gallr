import SwiftUI
import composeApp

struct NativeMyRoutes: View {
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @Environment(\.colorScheme) private var colorScheme
    let app: NativeGallrApp
    @State private var state: MyRoutesUiState?
    @State private var observation: NativeObservation?
    @State private var shareItems: [Any] = []
    @State private var showShare = false

    var body: some View {
        RoutePage(title: korean ? "내 동선" : "MY ROUTES") {
            RouteAction(title: korean ? "새 동선 만들기" : "MAKE A ROUTE", primary: true, action: app.myRoutes.startNewRoute)
                .accessibilityIdentifier("native-route-new")
            if let state {
                routeList(state)
                messages(state)
            } else {
                ProgressView()
            }
            RouteAction(title: korean ? "동선 둘러보기" : "BROWSE ROUTES") { navigation.push(.publicRoutes) }
        }
        .accessibilityIdentifier("native-my-routes")
        .onAppear {
            guard observation == nil else { return }
            observation = app.observeMyRoutes(onChange: receive)
            app.myRoutes.sectionShown()
        }
        .onChange(of: surfaceVisible) { visible in
            if visible, let state { receive(state) }
        }
        .onDisappear {
            observation?.cancel()
            observation = nil
        }
        .sheet(isPresented: $showShare) {
            NativeRouteActivitySheet(items: shareItems)
        }
        .confirmationDialog(
            korean ? "작성 중인 동선을 바꿀까요?" : "Replace your current draft?",
            isPresented: Binding(
                get: { state?.confirmNewRoute == true || state?.confirmOpen != nil },
                set: { if !$0 { app.myRoutes.dismissDialogs() } }
            ),
            titleVisibility: .visible
        ) {
            Button(korean ? "현재 초안 바꾸기" : "REPLACE DRAFT", role: .destructive) {
                if state?.confirmNewRoute == true {
                    app.myRoutes.confirmNewRoute()
                } else {
                    app.myRoutes.confirmOpen()
                }
            }
            Button(korean ? "이어서 편집" : "KEEP EDITING", action: app.myRoutes.keepEditing)
        }
        .confirmationDialog(
            korean ? "동선을 삭제할까요?" : "Delete this route?",
            isPresented: Binding(
                get: { state?.confirmDelete != nil },
                set: { if !$0 { app.myRoutes.dismissDialogs() } }
            ),
            titleVisibility: .visible
        ) {
            Button(korean ? "삭제" : "DELETE", role: .destructive, action: app.myRoutes.confirmDelete)
        }
        .confirmationDialog(
            korean ? "동선 공개 요청" : "REQUEST LISTING",
            isPresented: Binding(
                get: { state?.confirmListing != nil },
                set: { if !$0 { app.myRoutes.dismissDialogs() } }
            ),
            titleVisibility: .visible
        ) {
            Button(korean ? "공개 요청" : "REQUEST LISTING", action: app.myRoutes.confirmListing)
        } message: {
            if state?.confirmListing?.alsoPublishes == true {
                Text(korean
                    ? "공유 링크가 공개되고, 검토 후 동선 둘러보기에 표시될 수 있어요."
                    : "The share link will be public and the route may appear in Browse Routes after review.")
            } else {
                Text(korean ? "검토 후 공개 동선에 표시될 수 있어요." : "After review, the route may appear in Browse Routes.")
            }
        }
    }

    @ViewBuilder
    private func routeList(_ state: MyRoutesUiState) -> some View {
        if let draft = state.draftRow {
            Button {
                navigation.push(.routeComposer)
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(draft.name.isEmpty ? (korean ? "제목 없는 동선" : "Untitled route") : draft.name)
                        .font(.headline)
                    Text(korean ? "작성 중 · \(draft.stopCount)곳" : "DRAFT · \(draft.stopCount) STOPS")
                        .font(.caption)
                        .foregroundStyle(GallrStyle.secondary)
                }
                .frame(minHeight: 44)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("native-route-draft")
        }
        switch state.saved {
        case let loaded as SavedRoutesStateLoaded:
            ForEach(loaded.routes, id: \.id) { route in
                savedRow(route, busy: state.listingBusy.contains(route.id))
            }
            if loaded.routes.isEmpty && state.draftRow == nil {
                RouteInfo(text: korean ? "저장한 동선이 없어요." : "No saved routes yet.")
            }
        case is SavedRoutesStateSignedOut:
            RouteInfo(text: korean ? "로그인하면 동선을 저장하고 공유할 수 있어요." : "Sign in to save and share routes.")
            RouteAction(title: korean ? "로그인" : "SIGN IN") { navigation.push(.promptedSignIn) }
        case is SavedRoutesStateError:
            RouteInfo(text: korean ? "내 동선을 불러오지 못했어요." : "Could not load your routes.")
            RouteAction(title: korean ? "다시 시도" : "TRY AGAIN", action: app.myRoutes.refresh)
        default:
            ProgressView()
        }
    }

    @ViewBuilder
    private func savedRow(_ route: PersonalRouteSummary, busy: Bool) -> some View {
        HStack(alignment: .center, spacing: 8) {
            Button {
                app.myRoutes.open(route: route)
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(route.name).font(.headline)
                    Text(korean ? "\(route.stopCount)곳 · \(listingLabel(route.listingState))" : "\(route.stopCount) STOPS · \(listingLabel(route.listingState))")
                        .font(.caption)
                        .foregroundStyle(GallrStyle.secondary)
                }
                .frame(minHeight: 44)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            Menu {
                Button(korean ? "열기" : "OPEN") { app.myRoutes.open(route: route) }
                Button(korean ? "공유" : "SHARE") { app.myRoutes.share(route: route) }
                if route.listingState == .approved || route.listingState == .requested {
                    Button(korean ? "공개 철회" : "WITHDRAW LISTING") { app.myRoutes.withdrawListing(route: route) }
                        .disabled(busy)
                } else if route.listingState == .unlisted || route.listingState == .declined {
                    Button(korean ? "공개 요청" : "REQUEST LISTING") { app.myRoutes.requestListing(route: route) }
                        .disabled(busy)
                }
                Button(korean ? "삭제" : "DELETE", role: .destructive) { app.myRoutes.delete(route: route) }
            } label: {
                Image(systemName: "ellipsis")
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(korean ? "동선 메뉴" : "Route menu")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder
    private func messages(_ state: MyRoutesUiState) -> some View {
        if state.error != nil {
            RouteInfo(text: korean ? "동선을 처리하지 못했어요." : "The route could not be updated.")
            RouteAction(title: korean ? "닫기" : "DISMISS", action: app.myRoutes.dismissError)
        }
        if let message = state.listingMessage {
            if message is ListingMessageRequested {
                RouteInfo(text: korean ? "공개를 요청했어요." : "Listing requested.")
            } else if message is ListingMessageFailed {
                RouteInfo(text: korean ? "공개 상태를 바꾸지 못했어요." : "Could not change the listing.")
                RouteAction(title: korean ? "다시 시도" : "TRY AGAIN", action: app.myRoutes.retryListing)
            } else {
                RouteInfo(text: korean ? "공개 상태가 이미 바뀌었어요." : "The listing status has changed.")
            }
            RouteAction(title: korean ? "닫기" : "DISMISS", action: app.myRoutes.dismissListingMessage)
        }
    }

    private func receive(_ state: MyRoutesUiState) {
        self.state = state
        guard navigation.trails[navigation.selected]?.last?.route == .myRoutes else { return }
        if state.openComposer {
            app.myRoutes.onComposerOpened()
            navigation.push(.routeComposer)
        }
        if state.signInRequested {
            app.myRoutes.onSignInRequestHandled()
            navigation.push(.promptedSignIn)
        }
        if let share = state.sharePayload, !showShare {
            shareItems = routeShareItems(payload: share, language: korean ? .ko : .en, dark: colorScheme == .dark)
            showShare = true
            app.myRoutes.onShareSheetShown()
        }
    }

    private func listingLabel(_ state: RouteListingState) -> String {
        if state == .approved { return korean ? "공개 중" : "LISTED" }
        if state == .requested { return korean ? "검토 중" : "IN REVIEW" }
        if state == .removed { return korean ? "공개 철회됨" : "REMOVED" }
        if state == .declined { return korean ? "공개 보류" : "DECLINED" }
        return korean ? "비공개" : "UNLISTED"
    }
}
