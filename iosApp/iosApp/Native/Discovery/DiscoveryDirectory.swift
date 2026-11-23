import SwiftUI
import composeApp

struct DiscoveryEvent: View {
    let app: NativeGallrApp
    let id: String
    @EnvironmentObject private var navigation: GallrNavigation
    @StateObject private var context = DiscoveryContext()
    @ScaledMetric(relativeTo: .headline) private var heroHeight = 216.0
    @StateObject private var models = DiscoveryModels()
    @State private var model: EventDetailViewModel?
    @State private var state: EventDetailUiState?
    @State private var observation: NativeObservation?

    var body: some View {
        DiscoveryPage {
            if let ready = state as? EventDetailUiStateContent {
                let event = ready.event
                DiscoveryBanner(
                    title: event.localizedName(lang: context.language),
                    subtitle: event.localizedLocationLabel(lang: context.language),
                    image: event.coverImageUrl
                )
                .frame(height: heroHeight)
                Text(discoveryDateRange(start: event.startDate, end: event.endDate, language: context.language))
                    .font(context.font(.subheadline))
                let description = event.localizedDescription(lang: context.language)
                if !description.isEmpty { Text(description).font(context.font(.body)) }
                let venues = context.language == .ko ? ready.venuesKo : ready.venuesEn
                if !venues.isEmpty {
                    DiscoverySectionTitle(title: context.text("참여 공간", "Participating venues"))
                    Text(venues.joined(separator: " · ")).font(context.font(.subheadline))
                }
                if let address = event.ticketUrl, let url = URL(string: address) {
                    Link(context.text("티켓", "Tickets"), destination: url)
                        .buttonStyle(DiscoveryButtonStyle(prominent: true))
                }
                DiscoverySectionTitle(title: context.text("참여 전시", "Participating exhibitions"))
                if ready.exhibitions.isEmpty {
                    DiscoveryMessage(title: context.text("참여 전시가 없어요.", "No participating exhibitions."))
                }
                ForEach(ready.exhibitions, id: \.id) { exhibition in
                    DiscoveryExhibitionCard(
                        exhibition: exhibition, context: context, app: app,
                        open: {
                            app.recordExhibitionOpened(id: exhibition.id, surface: .eventDetail, kind: .event)
                            navigation.push(.exhibition(exhibition.id))
                        },
                        surface: .eventDetail
                    )
                }
            } else if state is EventDetailUiStateError || state is EventDetailUiStateNotFound {
                DiscoveryMessage(
                    title: context.text("이벤트를 불러올 수 없어요.", "Couldn’t load this event."),
                    actionTitle: context.text("다시 시도", "Retry"),
                    action: { model?.retry() }
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 180)
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear {
            context.connect(app)
            let detail = model ?? models.retain(app.eventDetail(id: id), app: app)
            model = detail
            observation = app.observeEventDetail(viewModel: detail) { state = $0 }
        }
        .onDisappear {
            observation?.cancel()
            observation = nil
            context.disconnect()
        }
    }
}

struct DiscoveryEditors: View {
    let app: NativeGallrApp
    @EnvironmentObject private var navigation: GallrNavigation
    @StateObject private var context = DiscoveryContext()
    @State private var state: EditorSelectorState?
    @State private var observation: NativeObservation?

    var body: some View {
        DiscoveryPage(title: context.text("에디터", "Editors")) {
            if let ready = state as? EditorSelectorState.Success {
                if ready.active.isEmpty && ready.past.isEmpty {
                    DiscoveryMessage(title: context.text("에디터가 없어요.", "No editors are available."))
                }
                editorSection(
                    context.text("활동 중인 에디터", "Current editors"),
                    editors: ready.active, counts: ready.exhibitionCounts
                )
                editorSection(
                    context.text("지난 에디터", "Past editors"),
                    editors: ready.past, counts: ready.exhibitionCounts
                )
            } else if state is EditorSelectorState.Error {
                DiscoveryMessage(
                    title: context.text("에디터를 불러올 수 없어요.", "Couldn’t load editors."),
                    actionTitle: context.text("다시 시도", "Retry"),
                    action: app.editorSelector.retry
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 180)
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear {
            context.connect(app)
            observation = app.observeEditorSelector { state = $0 }
        }
        .onDisappear {
            observation?.cancel()
            observation = nil
            context.disconnect()
        }
    }

    @ViewBuilder
    private func editorSection(_ title: String, editors: [Editor], counts: [String: KotlinInt]) -> some View {
        if !editors.isEmpty {
            DiscoverySectionTitle(title: title)
            ForEach(editors, id: \.id) { editor in
                Button {
                    navigation.push(.editor(editor.id))
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(editor.localizedName(lang: context.language)).font(context.font(.headline))
                            Text(editor.localizedTitle(lang: context.language)).font(context.font(.caption))
                            let count = counts[editor.id]?.intValue ?? 0
                            Text(context.text("전시 \(count)개", "\(count) exhibitions"))
                                .font(context.font(.caption))
                                .foregroundStyle(GallrStyle.secondary)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right").font(context.font(.caption))
                    }
                    .padding(16)
                    .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

struct DiscoveryEditor: View {
    let app: NativeGallrApp
    let id: String
    @EnvironmentObject private var navigation: GallrNavigation
    @StateObject private var context = DiscoveryContext()
    @StateObject private var models = DiscoveryModels()
    @State private var model: EditorDetailViewModel?
    @State private var state: EditorDetailUiState?
    @State private var observation: NativeObservation?

    var body: some View {
        DiscoveryPage(title: (state as? EditorDetailUiStateContent)?.editor?.localizedName(lang: context.language)) {
            if let ready = state as? EditorDetailUiStateContent {
                if let editor = ready.editor {
                    let title = editor.localizedTitle(lang: context.language)
                    let description = editor.localizedCurationDescription(lang: context.language)
                    if !title.isEmpty { Text(title).font(context.font(.headline)) }
                    if !description.isEmpty { Text(description).font(context.font(.body)) }
                }
                DiscoverySectionTitle(title: context.text("추천 전시", "Exhibition picks"))
                if ready.exhibitions.isEmpty {
                    DiscoveryMessage(title: context.text("추천 전시가 없어요.", "No exhibition picks are available."))
                }
                ForEach(ready.exhibitions, id: \.id) { exhibition in
                    DiscoveryExhibitionCard(
                        exhibition: exhibition, context: context, app: app,
                        open: {
                            app.recordExhibitionOpened(id: exhibition.id, surface: .editorDetail, kind: .editor)
                            navigation.push(.exhibition(exhibition.id))
                        },
                        surface: .editorDetail
                    )
                }
            } else if state is EditorDetailUiStateError {
                DiscoveryMessage(
                    title: context.text("에디터를 불러올 수 없어요.", "Couldn’t load this editor."),
                    actionTitle: context.text("다시 시도", "Retry"),
                    action: { model?.retry() }
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 180)
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear {
            context.connect(app)
            let detail = model ?? models.retain(app.editorDetail(id: id), app: app)
            model = detail
            observation = app.observeEditorDetail(viewModel: detail) { state = $0 }
        }
        .onDisappear {
            observation?.cancel()
            observation = nil
            context.disconnect()
        }
    }
}

struct DiscoveryGalleryRoute: View {
    let app: NativeGallrApp
    let id: String
    @StateObject private var context = DiscoveryContext()
    @State private var exhibition: Exhibition?
    @State private var complete = false
    @State private var observation: NativeObservation?

    var body: some View {
        Group {
            if let exhibition {
                DiscoveryGallery(app: app, context: context, exhibition: exhibition)
            } else if complete {
                DiscoveryMessage(
                    title: context.text("갤러리를 불러올 수 없어요.", "Couldn’t load this gallery."),
                    actionTitle: context.text("다시 시도", "Retry"),
                    action: resolve
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear {
            context.connect(app)
            if exhibition == nil { resolve() }
        }
        .onDisappear {
            observation?.cancel()
            context.disconnect()
        }
    }

    private func resolve() {
        observation?.cancel()
        complete = false
        if let found = app.findExhibition(id: id) {
            exhibition = found
            complete = true
        } else {
            observation = app.resolveExhibition(id: id) {
                exhibition = $0
                complete = true
            }
        }
    }
}

private struct DiscoveryGallery: View {
    let app: NativeGallrApp
    @ObservedObject var context: DiscoveryContext
    let exhibition: Exhibition
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.openURL) private var openURL
    @StateObject private var models = DiscoveryModels()
    @State private var model: GalleryDetailViewModel?
    @State private var state: GalleryDetailUiState?
    @State private var observation: NativeObservation?
    @State private var expanded = false

    var body: some View {
        DiscoveryPage(title: state?.snapshot.localizedName(lang: context.language)
            ?? exhibition.localizedVenueName(lang: context.language)) {
            if let state {
                if let avatar = state.profileImage?.imageUrl {
                    // Square by design: gallery avatar.
                    DiscoveryArtwork(url: avatar).frame(width: 80, height: 80)
                    if let credit = state.profileImage?.credit, !credit.isEmpty {
                        Text(context.text("사진: \(credit)", "Photo: \(credit)"))
                            .font(context.font(.caption))
                            .foregroundStyle(GallrStyle.secondary)
                    }
                }
                Text(state.snapshot.localizedLocation(lang: context.language)).foregroundStyle(GallrStyle.secondary)
                Button(state.isFollowing ? context.text("팔로잉", "Following") : context.text("팔로우", "Follow")) {
                    model?.toggleFollow()
                }
                .buttonStyle(DiscoveryButtonStyle(prominent: !state.isFollowing))
                .disabled(state.isSaving)

                if state.isFollowing {
                    Button(state.newExhibitionAlertsEnabled
                        ? context.text("알림 끄기", "Turn off alerts")
                        : context.text("새 전시 알림 받기", "Get new exhibition alerts")) {
                        model?.openAlertRationale()
                    }
                    .buttonStyle(DiscoveryButtonStyle())
                    .disabled(state.isSaving)
                }
                if state.permissionDenied {
                    Button(context.text("알림 설정 열기", "Open notification settings")) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                    .buttonStyle(DiscoveryButtonStyle())
                }
                if state.saveFailed { Text(context.text("저장하지 못했어요. 다시 시도해 주세요.", "Couldn’t save. Try again.")) }

                let current = state.currentExhibition ?? exhibition
                let address = current.localizedAddress(lang: context.language)
                if !address.isEmpty {
                    Button {
                        _ = app.openPlannerStop(exhibition: current, start: false)
                    } label: {
                        Label(address, systemImage: "location")
                    }
                    .buttonStyle(DiscoveryButtonStyle())
                }
                if let contact = current.contact, let url = discoveryContactURL(contact) {
                    Link(contact, destination: url).buttonStyle(DiscoveryButtonStyle())
                }
                programme(state)
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 180)
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear {
            let detail = model ?? models.retain(app.galleryDetail(exhibition: exhibition), app: app)
            model = detail
            observation = app.observeGalleryDetail(viewModel: detail) { state = $0 }
        }
        .onDisappear {
            observation?.cancel()
            observation = nil
        }
        .alert(context.text("새 전시 알림 받기", "Get new exhibition alerts"),
               isPresented: Binding(get: { state?.showAlertRationale == true }, set: {
                   if !$0 { model?.dismissAlertRationale() }
               })) {
            Button(context.text("알림 허용", "Allow notifications")) { model?.enableAlerts() }
            Button(context.text("나중에", "Later"), role: .cancel) { model?.dismissAlertRationale() }
        } message: {
            Text(context.text("이 갤러리에서 새 전시가 열리면 알려드려요.", "We’ll let you know when this gallery has a new exhibition."))
        }
    }

    @ViewBuilder
    private func programme(_ state: GalleryDetailUiState) -> some View {
        let visible = expanded ? state.exhibitions : Array(state.exhibitions.prefix(3))
        let visited = Set(state.visitedExhibitions.map(\.exhibitionId))
        DiscoverySectionTitle(title: context.text("전시 프로그램", "Exhibitions"))
        ForEach(visible, id: \.id) { exhibition in
            DiscoveryExhibitionCard(
                exhibition: exhibition, context: context, app: app,
                open: {
                    app.recordExhibitionOpened(id: exhibition.id, surface: .galleryDetail, kind: .gallery)
                    navigation.push(.exhibition(exhibition.id))
                },
                surface: .galleryDetail,
                visited: visited.contains(exhibition.id)
            )
        }
        if state.exhibitions.count > 3 {
            Button(expanded ? context.text("접기", "Show less") : context.text("전체 보기", "Show all")) { expanded.toggle() }
                .buttonStyle(DiscoveryButtonStyle())
        }
    }
}
