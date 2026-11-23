import SwiftUI
import composeApp

struct MyRootView: View {
    let app: NativeGallrApp

    @EnvironmentObject private var navigation: GallrNavigation
    @EnvironmentObject private var accountProfile: NativeAccountProfile
    @Environment(\.gallrKorean) private var korean
    @State private var state: MyGallrUiState?
    @State private var diaryCount = 0
    @State private var observations: [NativeObservation] = []
    @State private var section: MyGallrSection = .diary

    var body: some View {
        MyGallrScroll {
            HStack(alignment: .center, spacing: 16) {
                NativeAvatar(url: accountProfile.avatarURL)
                VStack(alignment: .leading, spacing: 4) {
                    Text(accountProfile.displayName ?? (korean ? "나의 gallr" : "My gallr"))
                        .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                        .accessibilityAddTraits(.isHeader)
                    Button(accountProfile.user == nil
                        ? (korean ? "로그인" : "Sign in")
                        : (korean ? "프로필" : "Profile")) { navigation.push(.account) }
                        .accessibilityIdentifier("gallr-my-account")
                }
                Spacer()
            }
            HStack(spacing: 20) {
                sectionButton(korean ? "일기" : "DIARY", count: diaryCount, .diary)
                sectionButton(korean ? "팔로잉" : "FOLLOWING", count: state?.followedGalleries.count ?? 0, .following)
                if app.routesEnabled {
                    Button(korean ? "동선" : "ROUTES") { navigation.push(.myRoutes) }
                        .accessibilityIdentifier("gallr-my-routes")
                }
            }
            Divider()
            if accountProfile.user == nil, state?.isAccountNudgeLoaded == true, state?.isAccountNudgeDismissed == false,
               diaryCount > 0 || !(state?.followedGalleries.isEmpty ?? true) {
                HStack {
                    Button(korean ? "로그인하고 기록 저장하기" : "Sign in to keep your archive") {
                        navigation.push(.promptedSignIn)
                    }
                    Spacer()
                    Button(korean ? "닫기" : "Dismiss", action: app.myGallr.dismissAccountNudge)
                }
            }
            if section == .following {
                NativeFollowingList(app: app, state: state)
            } else {
                NativeDiaryList(app: app, publishedInitially: false, embedded: true)
            }
        }
        .onAppear {
            observations = [
                app.observeMyGallr { state = $0 },
                app.observeDiary { diaryCount = Int($0.entryCount) },
            ]
        }
        .onDisappear {
            observations.forEach { $0.cancel() }
            observations = []
        }
    }

    private func sectionButton(_ title: String, count: Int, _ choice: MyGallrSection) -> some View {
        Button {
            section = choice
        } label: {
            VStack(spacing: 4) {
                Text(count.formatted()).font(.title3)
                Text(title).font(GallrStyle.font(13, weight: .medium, korean: korean, relativeTo: .caption))
            }
            .padding(.vertical, 8)
            .overlay(alignment: .bottom) {
                if section == choice { Rectangle().fill(GallrStyle.accent).frame(height: 4) }
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(section == choice ? [.isSelected] : [])
    }
}

struct MyDestinationView: View {
    let route: GallrRoute
    let app: NativeGallrApp

    var body: some View {
        switch route {
        case .account, .profile, .editProfile:
            NativeAccountView(app: app, prompted: false, recovery: false)
        case .promptedSignIn:
            NativeAccountView(app: app, prompted: true, recovery: false)
        case .passwordRecovery:
            NativeAccountView(app: app, prompted: false, recovery: true)
        case .settings:
            NativeSettingsView(app: app)
        case let .diaryEntry(id):
            NativeDiaryEntryView(app: app, exhibitionID: id, focusNote: false)
        case let .diaryNote(id):
            NativeDiaryEntryView(app: app, exhibitionID: id, focusNote: true)
        case .diary, .publishedDiary:
            MyGallrScroll {
                NativeDiaryList(app: app, publishedInitially: route == .publishedDiary, embedded: false)
            }
        case .following:
            NativeFollowingView(app: app)
        case .addVisits, .addEndedVisits, .addGalleries:
            NativeArchiveAddView(app: app, route: route)
        case .routeComposer, .seededRouteComposer, .publicRoute, .publicRoutes, .myRoutes, .routePlanner:
            NativeRoutes(route: route, app: app)
        case .exhibition, .exhibitionNote, .exhibitionShare, .exhibitionNotification, .recommendationExhibition, .recommendationShare,
             .gallery, .event, .editor, .editors, .collection, .recommendations:
            CatalogueDestinationView(route: route, app: app)
        }
    }
}

private struct NativeFollowingView: View {
    let app: NativeGallrApp
    @State private var state: MyGallrUiState?
    @State private var observation: NativeObservation?

    var body: some View {
        MyGallrScroll { NativeFollowingList(app: app, state: state) }
            .onAppear { observation = app.observeMyGallr { state = $0 } }
            .onDisappear { observation?.cancel() }
    }
}

private struct NativeFollowingList: View {
    let app: NativeGallrApp
    let state: MyGallrUiState?

    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @State private var galleryToUnfollow: FollowedGalleryUi?
    private var language: AppLanguage { korean ? .ko : .en }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(korean ? "갤러리 추가" : "Add galleries") {
                navigation.push(.addGalleries)
            }
            if let state, !state.isLoading {
                if state.followingLoadFailed {
                    Text(korean ? "팔로잉을 불러오지 못했어요." : "Couldn’t load followed galleries.")
                        .foregroundStyle(GallrStyle.secondary)
                } else if state.followedGalleries.isEmpty {
                    Text(korean ? "팔로우한 갤러리가 없어요." : "No followed galleries yet.")
                        .foregroundStyle(GallrStyle.secondary)
                }
                ForEach(state.followedGalleries, id: \.record.galleryKey) { gallery in
                    HStack {
                        Button {
                            if let exhibition = gallery.latestRelevantExhibition {
                                app.myGallr.acknowledgeGallery(galleryKey: gallery.record.galleryKey)
                                navigation.push(.gallery(exhibition.id))
                            }
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(gallery.snapshot.localizedName(lang: language))
                                Text(gallery.snapshot.localizedLocation(lang: language))
                                    .font(.footnote)
                                    .foregroundStyle(GallrStyle.secondary)
                                if !gallery.unseenExhibitions.isEmpty {
                                    Text(korean ? "새 전시 \(gallery.unseenExhibitions.count)개"
                                         : "\(gallery.unseenExhibitions.count) new exhibitions")
                                        .font(.footnote)
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(gallery.latestRelevantExhibition == nil)
                        Button(korean ? "팔로우 취소" : "Unfollow") { galleryToUnfollow = gallery }
                            .font(.footnote)
                    }
                    Divider()
                }
            } else {
                ProgressView()
            }
        }
        .confirmationDialog(
            korean ? "이 갤러리를 팔로우 취소할까요?" : "Unfollow this gallery?",
            isPresented: Binding(get: { galleryToUnfollow != nil }, set: { if !$0 { galleryToUnfollow = nil } }),
            titleVisibility: .visible,
            presenting: galleryToUnfollow
        ) { gallery in
            Button(korean ? "팔로우 취소" : "Unfollow", role: .destructive) {
                app.myGallr.unfollowGallery(galleryKey: gallery.record.galleryKey)
            }
            Button(korean ? "취소" : "Cancel", role: .cancel) {}
        }
    }
}

private struct NativeArchiveAddView: View {
    let app: NativeGallrApp
    let route: GallrRoute

    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrNavigationTab) private var ownerTab
    @Environment(\.gallrKorean) private var korean
    @State private var state: MyGallrUiState?
    @State private var observation: NativeObservation?
    @State private var search = ""
    private var addingGalleries: Bool { route == .addGalleries }
    private var language: AppLanguage { korean ? .ko : .en }

    var body: some View {
        MyGallrScroll {
            Text(addingGalleries ? (korean ? "갤러리 추가" : "Add galleries")
                 : (korean ? "지난 방문 추가" : "Add past visits"))
                .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                .accessibilityAddTraits(.isHeader)
            if let state {
                TextField(korean ? "검색" : "Search", text: Binding(
                    get: { search },
                    set: {
                        search = $0
                        if addingGalleries {
                            app.myGallr.setGallerySearchQuery(query: $0)
                        } else {
                            app.myGallr.setSearchQuery(query: $0)
                        }
                    }
                ))
                .modifier(NativeFieldStyle())
                if addingGalleries {
                    ForEach(state.availableGalleryCandidates, id: \.galleryKey) { candidate in
                        row(candidate.snapshot.localizedName(lang: language),
                            selected: state.selectedGalleryKeys.contains(candidate.galleryKey)) {
                            app.myGallr.toggleGallerySelection(galleryKey: candidate.galleryKey)
                        }
                    }
                } else {
                    ForEach(state.availableExhibitions, id: \.id) { exhibition in
                        row(exhibition.localizedName(lang: language),
                            selected: state.selectedExhibitionIds.contains(exhibition.id)) {
                            app.myGallr.toggleSelection(exhibitionId: exhibition.id)
                        }
                        .accessibilityIdentifier("gallr-diary-add-\(exhibition.id)")
                    }
                }
                if state.saveFailed || state.followSaveFailed {
                    Text(korean ? "저장하지 못했어요." : "Couldn’t save.")
                }
                NativePrimaryButton(korean ? "저장" : "Save",
                                    enabled: addingGalleries ? state.canSaveFollows : state.canSave) {
                    if addingGalleries {
                        app.myGallr.saveSelectedGalleries()
                    } else {
                        app.myGallr.saveSelected()
                    }
                }
                .accessibilityIdentifier("gallr-archive-save")
            } else {
                ProgressView()
            }
        }
        .onAppear {
            if addingGalleries {
                app.myGallr.startAddingGalleries()
            } else if route == .addEndedVisits {
                app.myGallr.startAddingEndedSaved()
            } else {
                app.myGallr.startAddingVisits()
            }
            observation = app.observeMyGallr { next in
                let wasEditing = state != nil && state?.mode != .archive
                state = next
                if wasEditing && next.mode == .archive {
                    navigation.back(on: ownerTab)
                }
            }
        }
        .onDisappear {
            observation?.cancel()
            if addingGalleries {
                app.myGallr.cancelAddingGalleries()
            } else {
                app.myGallr.cancelAddingVisits()
            }
        }
    }

    private func row(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(GallrStyle.accent) }
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

struct MyGallrScroll<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16, content: content)
                .frame(maxWidth: 560, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity)
        }
    }
}
