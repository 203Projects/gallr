import SwiftUI
import composeApp

struct DiscoveryExhibitionRoute: View {
    let app: NativeGallrApp
    let id: String
    let route: GallrRoute
    @StateObject private var context = DiscoveryContext()
    @State private var exhibition: Exhibition?
    @State private var finished = false
    @State private var observation: NativeObservation?

    var body: some View {
        Group {
            if let exhibition {
                DiscoveryExhibition(
                    app: app, context: context, exhibition: exhibition,
                    showNote: route == .exhibitionNote(id),
                    startSharing: route == .exhibitionShare(id) || route == .recommendationShare(id),
                    showReason: route == .recommendationExhibition(id) || route == .recommendationShare(id),
                    entryPoint: route == .exhibitionNotification(id) ? .notification : .card
                )
            } else if finished {
                DiscoveryMessage(
                    title: context.text("전시를 찾을 수 없어요.", "Couldn’t find this exhibition."),
                    actionTitle: context.text("다시 시도", "Retry"), action: resolve
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
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
        finished = false
        if let available = app.findExhibition(id: id) {
            exhibition = available
            finished = true
        } else {
            observation = app.resolveExhibition(id: id) {
                exhibition = $0
                finished = true
            }
        }
    }
}

private struct DiscoveryExhibition: View {
    let app: NativeGallrApp
    @ObservedObject var context: DiscoveryContext
    let exhibition: Exhibition
    let showNote: Bool
    let startSharing: Bool
    let showReason: Bool
    let entryPoint: AnalyticsEntryPoint
    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.openURL) private var openURL
    @StateObject private var models = DiscoveryModels()
    @State private var diary: DiaryEntryUiState?
    @State private var diaryModel: DiaryEntryViewModel?
    @State private var note: CuratorNote?
    @State private var images: [ExhibitionImage] = []
    @State private var imageIndex = 0
    @State private var observations: [NativeObservation] = []
    @State private var noteModel: CuratorNoteViewModel?
    @State private var thoughtModel: ExhibitionThoughtsViewModel?
    @State private var thoughts: ExhibitionThoughtsUiState?
    @State private var routeModel: AddToRouteViewModel?
    @State private var routeState: AddToRouteUiState?
    @State private var reason: String?
    @State private var sharing = false
    @State private var handledInitialShare = false
    @State private var hasScrolledToNote = false
    @State private var pendingHide: PublicThought?
    @State private var hiddenAuthor: String?
    @State private var pendingUnpublish: PublicThought?
    @State private var moderationMessage: String?
    @State private var didRecordPage = false

    var body: some View {
        ScrollViewReader { scroll in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    gallery
                    VStack(alignment: .leading, spacing: 24) {
                        exhibitionHeading
                        exhibitionActions
                        facts
                        if let note { editorNote(note).id("editor-note") }
                        let description = exhibition.localizedDescriptionAndCredits(lang: context.language)
                        if !description.isEmpty {
                            DiscoverySectionTitle(title: context.text("전시 소개", "About the exhibition"))
                            Text(description)
                        }
                        publishedNotes
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                    .frame(maxWidth: 960)
                    .frame(maxWidth: .infinity)
                }
            }
            .onChange(of: note?.versionNumber) { _ in
                if showNote, note != nil, !hasScrolledToNote {
                    hasScrolledToNote = true
                    scroll.scrollTo("editor-note", anchor: .top)
                }
            }
        }
        .discoveryStyle(language: context.language)
        .onAppear(perform: connect)
        .onDisappear(perform: disconnect)
        .onChange(of: scenePhase) { scene in
            if scene == .active, surfaceVisible, !observations.isEmpty { thoughtModel?.onPageVisible() }
            if scene != .active { thoughtModel?.onPageHidden() }
        }
        .onChange(of: surfaceVisible) { visible in
            if visible, scenePhase == .active {
                thoughtModel?.onPageShown()
                thoughtModel?.onPageVisible()
                if !sharing { recordPage() }
            } else {
                thoughtModel?.onPageHidden()
            }
        }
        .onChange(of: sharing) { showing in
            if !showing { recordPage() }
        }
        .sheet(isPresented: $sharing) {
            DiscoverySharePreview(exhibition: exhibition, language: context.language) {
                recordIntent(.shareSheetOpened)
            }
        }
        .sheet(isPresented: Binding(get: { thoughts?.reportTarget != nil }, set: { if !$0 { thoughtModel?.closeReport() } })) {
            if let thoughtModel { reportSheet(thoughtModel) }
        }
        .confirmationDialog(context.text("이 사용자의 감상을 숨길까요?", "Hide notes from this author?"),
                            isPresented: Binding(get: { pendingHide != nil }, set: { if !$0 { pendingHide = nil } })) {
            Button(context.text("숨기기", "Hide"), role: .destructive) {
                if let pendingHide {
                    hiddenAuthor = pendingHide.authorId
                    thoughtModel?.hideAuthor(thought: pendingHide)
                }
                pendingHide = nil
            }
        }
        .confirmationDialog(context.text("감상 공개를 취소할까요?", "Unpublish this note?"),
                            isPresented: Binding(get: { pendingUnpublish != nil }, set: { if !$0 { pendingUnpublish = nil } })) {
            Button(context.text("공개 취소", "Unpublish"), role: .destructive) {
                if let pendingUnpublish { thoughtModel?.unpublishOwn(thought: pendingUnpublish) }
                pendingUnpublish = nil
            }
        }
    }

    private func connect() {
        guard observations.isEmpty else { return }
        let notes = noteModel ?? models.retain(app.curatorNote(id: exhibition.id), app: app)
        noteModel = notes
        let thought = thoughtModel ?? models.retain(app.exhibitionThoughts(id: exhibition.id), app: app)
        thoughtModel = thought
        let add = routeModel ?? models.retain(app.addToRoute(exhibition: exhibition), app: app)
        routeModel = add
        let entry = diaryModel ?? models.retain(app.diaryEntry(exhibition: exhibition), app: app)
        diaryModel = entry
        observations = [
            app.observeCuratorNote(viewModel: notes) { note = ($0 as? CuratorNoteUiStateReady)?.note },
            app.observeCuratorImages(viewModel: notes) { images = $0 },
            app.observeExhibitionThoughts(viewModel: thought) {
                thoughts = $0
                if let message = $0.message {
                    present(message)
                    thought.messageShown()
                }
                if $0.signInRequested {
                    thought.onSignInRequestHandled()
                    navigation.push(.promptedSignIn)
                }
            },
            app.observeAuth { thought.onAuthStateChanged(authState: $0) },
            app.observeAddToRoute(viewModel: add) { routeState = $0 },
            app.observeDiaryEntry(viewModel: entry) {
                diary = $0
                thought.onOwnNoteChanged(note: $0.publicNote, status: $0.noteStatus)
            },
            app.observeRecommendations { recommendation in
                if showReason, let match = (recommendation as? RecommendationUiStateReady)?.items.first(where: {
                    $0.exhibition.id == exhibition.id
                }) {
                    reason = app.recommendationReason(recommendation: match, language: context.language)
                }
            }
        ]
        entry.onPageShown()
        thought.onPageShown()
        if scenePhase == .active, surfaceVisible { thought.onPageVisible() }
        if startSharing, !handledInitialShare {
            handledInitialShare = true
            sharing = true
        } else {
            recordPage()
        }
    }

    private func recordPage() {
        guard surfaceVisible, !didRecordPage else { return }
        didRecordPage = true
        app.recordSurface(surface: .exhibitionDetail, entryPoint: entryPoint)
    }

    private func recordIntent(_ action: AnalyticsIntentAction) {
        app.recordExhibitionIntent(id: exhibition.id, surface: .exhibitionDetail, action: action)
    }

    private func disconnect() {
        observations.forEach { $0.cancel() }
        observations.removeAll()
        thoughtModel?.onPageHidden()
    }

    private var gallery: some View {
        VStack(alignment: .trailing, spacing: 8) {
            // Square by design: the exhibition image pager runs to the screen edge.
            TabView(selection: $imageIndex) {
                DiscoveryArtwork(
                    url: exhibition.coverImageUrl,
                    caption: exhibition.localizedName(lang: context.language),
                    fill: false, imageIdentifier: "exhibition.cover.\(exhibition.id)"
                )
                .tag(0)
                ForEach(Array(images.enumerated()), id: \.offset) { offset, image in
                    DiscoveryArtwork(url: image.url, caption: image.alt(lang: context.language), fill: false)
                        .tag(offset + 1)
                }
            }
            .frame(height: 430)
            .tabViewStyle(.page(indexDisplayMode: .never))
            if !images.isEmpty {
                HStack {
                    if imageIndex > 0, images.indices.contains(imageIndex - 1) {
                        let image = images[imageIndex - 1]
                        if !image.credit.isEmpty { Text(image.credit).font(context.font(.caption)) }
                    }
                    Spacer()
                    Button {
                        imageIndex = (imageIndex + 1) % (images.count + 1)
                    } label: {
                        Text("\(imageIndex + 1) / \(images.count + 1)")
                            .monospacedDigit()
                            .font(context.font(.caption))
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(context.text("다음 사진", "Next image"))
                    .accessibilityValue("\(imageIndex + 1) / \(images.count + 1)")
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private var exhibitionHeading: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 12))
        return layout {
            VStack(alignment: .leading, spacing: 8) {
                Text(exhibition.localizedName(lang: context.language))
                    .font(GallrStyle.font(26, weight: .bold, korean: context.language == .ko, relativeTo: .title))
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("exhibition.title.\(exhibition.id)")
                if let reason, !reason.isEmpty {
                    Text(reason).font(context.font(.subheadline)).foregroundStyle(GallrStyle.secondary)
                }
                Button {
                    recordIntent(.galleryOpen)
                    navigation.push(.gallery(exhibition.id))
                } label: {
                    Label(exhibition.localizedVenueName(lang: context.language), systemImage: "arrow.up.right")
                        .font(context.font(.subheadline, weight: .semibold))
                        .frame(minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.plain)
                Text(exhibition.localizedDateRange(lang: context.language))
                    .font(context.font(.subheadline)).foregroundStyle(GallrStyle.secondary)
                if let today = discoveryToday(),
                   let status = ExhibitionStatusKt.exhibitionStatus(
                    openingDate: exhibition.openingDate, closingDate: exhibition.closingDate, today: today
                   ).label(lang: context.language) {
                    Text(status)
                        .font(context.font(.caption, weight: .semibold))
                        .padding(6)
                        .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
            HStack(spacing: 4) {
                DiscoveryBookmark(app: app, context: context, exhibition: exhibition)
                Button { sharing = true } label: {
                    Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(context.text("공유", "Share"))
            }
        }
    }

    private var exhibitionActions: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let row = diary?.row {
                let hasEntry = row is DiaryVisitRowStateDated || row is DiaryVisitRowStateUndated
                Button {
                    navigation.push(.diaryEntry(exhibition.id))
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let dated = row as? DiaryVisitRowStateDated {
                            Text(discoveryDateRange(start: dated.date, end: dated.date, language: context.language))
                                .font(context.font(.caption))
                        }
                        Text(hasEntry ? context.text("일기 편집", "Edit diary entry") : context.text("일기 쓰기", "Write a diary entry"))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(DiscoveryButtonStyle())
            }
            if app.routesEnabled, let routeState, routeState.availability != .hidden {
                if routeState.availability == .canAdd {
                    Button(context.text("동선에 추가", "Add to route")) { routeModel?.add() }
                        .buttonStyle(DiscoveryButtonStyle())
                } else {
                    Button(context.text("동선 열기", "Open route")) { navigation.push(.routeComposer) }
                        .buttonStyle(DiscoveryButtonStyle())
                }
                if let message = routeState.message {
                    Text(context.text("동선에 추가했어요 · \(message.stopCount)곳", "Added to route · \(message.stopCount) stops"))
                        .font(context.font(.caption))
                        .foregroundStyle(GallrStyle.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var facts: some View {
        let address = exhibition.localizedAddress(lang: context.language)
        if !address.isEmpty || exhibition.hours != nil || exhibition.ticketUrl != nil ||
            exhibition.contact != nil || exhibition.openingTime != nil || exhibition.receptionDate != nil {
            VStack(alignment: .leading, spacing: 8) {
                DiscoverySectionTitle(title: context.text("관람 정보", "Visitor information"))
                if !address.isEmpty {
                    Button {
                        if app.openPlannerStop(exhibition: exhibition, start: false) {
                            recordIntent(.openMaps)
                        }
                    } label: {
                        Label(address, systemImage: "location")
                    }
                    .buttonStyle(DiscoveryButtonStyle())
                }
                if let hours = exhibition.hours, !hours.isEmpty { Text(hours).font(context.font(.subheadline)) }
                if let opening = exhibition.openingTime, !opening.isEmpty {
                    Text(context.text("오프닝 \(opening)", "Opening \(opening)")).font(context.font(.subheadline))
                }
                if let reception = exhibition.receptionDate {
                    Text(context.text("리셉션 \(reception)", "Reception \(reception)")).font(context.font(.subheadline))
                }
                if let contact = exhibition.contact, let url = discoveryContactURL(contact) {
                    Button(contact) {
                        openURL(url) { opened in if opened { recordIntent(.contact) } }
                    }
                    .buttonStyle(DiscoveryButtonStyle())
                }
                if let ticket = exhibition.ticketUrl, let url = URL(string: ticket) {
                    Button(context.text("예매", "Get tickets")) {
                        openURL(url) { opened in if opened { recordIntent(.ticket) } }
                    }
                    .buttonStyle(DiscoveryButtonStyle(prominent: true))
                }
            }
        }
    }

    private func editorNote(_ note: CuratorNote) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            DiscoverySectionTitle(title: context.text("에디터의 시선", "Editor’s eye"))
                .accessibilityIdentifier("exhibition.editor-note")
            Text(note.hook(lang: context.language)).font(context.font(.title3, weight: .bold))
            ForEach(Array(note.insights(lang: context.language).enumerated()), id: \.offset) { index, paragraph in
                if images.indices.contains(index) {
                    let image = images[index]
                    DiscoveryArtwork(url: image.url, caption: image.alt(lang: context.language))
                        .aspectRatio(CGFloat(image.frameAspectRatio()), contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                    if !image.credit.isEmpty { Text(image.credit).font(context.font(.caption)).foregroundStyle(GallrStyle.secondary) }
                }
                Text(paragraph)
            }
            let tip = note.tip(lang: context.language)
            if !tip.isEmpty { Text(tip).font(context.font(.subheadline, weight: .medium)) }
        }
    }

    private var publishedNotes: some View {
        VStack(alignment: .leading, spacing: 12) {
            DiscoverySectionTitle(title: context.text("감상", "Notes"))
            if diary?.row != nil {
                Button(context.text("감상 남기기", "Write a note")) { navigation.push(.diaryNote(exhibition.id)) }
                    .buttonStyle(DiscoveryButtonStyle())
            }
            if let own = diary?.publicNote, let status = diary?.noteStatus, status != .published,
               status != .private_, let label = noteStatus(status) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(label).font(context.font(.caption, weight: .semibold))
                    Text(own.content)
                    Button(context.text("수정", "Edit")) { navigation.push(.diaryNote(exhibition.id)) }
                        .buttonStyle(DiscoveryButtonStyle())
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
            }
            if let moderationMessage {
                Text(moderationMessage).font(context.font(.subheadline)).foregroundStyle(GallrStyle.secondary)
            }
            if thoughts?.loadFailed == true {
                DiscoveryMessage(
                    title: context.text("감상을 불러올 수 없어요.", "Couldn’t load notes."),
                    actionTitle: context.text("다시 시도", "Retry"), action: { thoughtModel?.refresh() }
                )
            } else if thoughts?.isLoading == true {
                ProgressView()
            } else if let thoughts, thoughts.thoughts.isEmpty {
                Text(thoughts.onlyHiddenNotes
                    ? context.text("표시할 감상이 없어요.", "No visible notes.")
                    : context.text("아직 공개된 감상이 없어요.", "No published notes yet."))
                    .font(context.font(.subheadline)).foregroundStyle(GallrStyle.secondary)
            }
            ForEach(thoughts?.thoughts ?? [], id: \.id) { thought in
                thoughtCard(thought)
            }
            if let hiddenAuthor {
                Button(context.text("숨기기 되돌리기", "Undo hiding author")) {
                    thoughtModel?.undoHideAuthor(userId: hiddenAuthor)
                    self.hiddenAuthor = nil
                }
                .buttonStyle(DiscoveryButtonStyle())
            }
        }
    }

    private func thoughtCard(_ thought: PublicThought) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                if let url = thought.authorAvatarUrl {
                    DiscoveryArtwork(url: url).frame(width: 32, height: 32).clipShape(Circle())
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(thought.authorDisplayName.isEmpty
                        ? context.text("gallr 방문자", "gallr visitor")
                        : thought.authorDisplayName)
                        .font(context.font(.subheadline, weight: .semibold))
                    let created = Date(timeIntervalSince1970: Double(thought.createdAt.toEpochMilliseconds()) / 1_000)
                    Text(created.formatted(.dateTime.month().day().year()
                        .locale(Locale(identifier: context.text("ko_KR", "en_US")))))
                        .font(context.font(.caption))
                        .foregroundStyle(GallrStyle.secondary)
                }
                Spacer()
                Menu {
                    if thoughts?.currentUserId == thought.authorId {
                        Button(context.text("수정", "Edit")) { navigation.push(.diaryNote(exhibition.id)) }
                        Button(context.text("공개 취소", "Unpublish"), role: .destructive) {
                            pendingUnpublish = thought
                        }
                    } else {
                        Button(thought.reportedByMe ? context.text("신고함", "Reported") : context.text("신고", "Report")) {
                            thoughtModel?.startReport(thought: thought)
                        }
                        .disabled(thought.reportedByMe)
                        Button(context.text("이 사용자의 감상 숨기기", "Hide this author’s notes")) {
                            pendingHide = thought
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis").frame(width: 44, height: 44)
                }
                .accessibilityLabel(context.text("감상 옵션", "Note options"))
            }
            Text(thought.content)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GallrStyle.mutedSurface, in: RoundedRectangle(cornerRadius: 16))
    }

    private func noteStatus(_ status: PublicNoteStatus) -> String? {
        if status == .sending { return context.text("보내는 중", "Sending") }
        if status == .retry { return context.text("다시 시도 필요", "Needs retry") }
        if status == .inReview { return context.text("검토 중", "In review") }
        if status == .notPublished { return context.text("공개 안 됨", "Not published") }
        return nil
    }

    private func present(_ message: ThoughtsMessage) {
        switch message {
        case is ThoughtsMessageReported, is ThoughtsMessageAlreadyReported:
            moderationMessage = context.text("감상 신고가 접수되었어요.", "Your report was received.")
        case is ThoughtsMessageCheckConnection:
            moderationMessage = context.text("연결을 확인해 주세요.", "Check your connection and try again.")
        case let hidden as ThoughtsMessageAuthorHidden:
            hiddenAuthor = hidden.userId
            moderationMessage = context.text("이 사용자의 감상을 숨겼어요.", "This author’s notes are hidden.")
        default:
            moderationMessage = context.text("변경하지 못했어요. 다시 시도해 주세요.", "Couldn’t make the change. Try again.")
        }
    }

    private func reportSheet(_ model: ExhibitionThoughtsViewModel) -> some View {
        DiscoveryPage(title: context.text("감상 신고", "Report note")) {
            reportOption(.inappropriate, context.text("부적절한 내용", "Inappropriate content"), model)
            reportOption(.promotional, context.text("광고 또는 홍보", "Advertising or promotion"), model)
            reportOption(.notAboutExhibition, context.text("전시와 무관함", "Unrelated to the exhibition"), model)
            reportOption(.personalInformation, context.text("개인정보 포함", "Personal information"), model)
            reportOption(.other, context.text("기타", "Other"), model)
            Button(context.text("취소", "Cancel")) { model.closeReport() }
                .buttonStyle(DiscoveryButtonStyle())
        }
    }

    private func reportOption(_ reason: ThoughtReportReason, _ label: String, _ model: ExhibitionThoughtsViewModel) -> some View {
        Button(label) { model.report(reason: reason) }
            .buttonStyle(DiscoveryButtonStyle())
            .disabled(thoughts?.reportBusy == true)
    }
}

func discoveryContactURL(_ contact: String) -> URL? {
    let value = contact.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !value.isEmpty else { return nil }
    if value.hasPrefix("https://") || value.hasPrefix("http://") { return URL(string: value) }
    if value.contains("@"), !value.contains(" ") { return URL(string: "mailto:\(value)") }
    let number = value.filter { $0.isNumber || $0 == "+" }
    if number.count > 6 { return URL(string: "tel:\(number)") }
    return nil
}
