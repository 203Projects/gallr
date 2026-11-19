import SwiftUI
import composeApp

struct NativeDiaryList: View {
    let app: NativeGallrApp
    let publishedInitially: Bool
    let embedded: Bool

    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @Environment(\.gallrSurfaceVisible) private var surfaceVisible
    @State private var state: DiaryUiState?
    @State private var observation: NativeObservation?
    @State private var selectedFilter: DiaryFilter = .all

    private var language: AppLanguage { korean ? .ko : .en }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if !embedded {
                Text(korean ? "일기" : "DIARY")
                    .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
            }
            HStack(spacing: 16) {
                filter(korean ? "전체" : "All", .all)
                filter(korean ? "공개한 감상" : "Public notes", .publishedNotes)
            }
            if let state, let diary = state.diary {
                if state.approvalLinePending {
                    HStack {
                        Text(korean ? "감상이 공개됐어요" : "Your note is published")
                        Spacer()
                        Button(korean ? "닫기" : "Dismiss", action: app.diary.acknowledgeApprovals)
                    }
                }
                if diary.showEndedSavedStrip {
                    HStack {
                        Text(korean ? "저장한 전시 \(diary.endedSavedCount)개가 끝났어요"
                             : "\(diary.endedSavedCount) saved exhibitions have ended")
                        Spacer()
                        Button(korean ? "추가하기" : "Add") { navigation.push(.addEndedVisits) }
                        Button(korean ? "닫기" : "Dismiss", action: app.diary.dismissEndedSavedStrip)
                    }
                }
                if diary.months.isEmpty && diary.undated.isEmpty {
                    Text(korean ? "아직 기록이 없어요." : "No diary entries yet.")
                        .foregroundStyle(GallrStyle.secondary)
                }
                ForEach(diary.months, id: \.self) { month in
                    let label = korean ? "\(month.year)년 \(month.month)월" : monthTitle(month)
                    entries(month.entries, title: label)
                }
                if !diary.undated.isEmpty {
                    entries(diary.undated, title: korean ? "날짜 미정" : "Date unspecified")
                }
            } else if state?.loadFailed == true {
                Button(korean ? "다시 시도" : "Retry", action: app.diary.retry)
            } else {
                ProgressView()
            }
            Button(korean ? "지난 방문 추가" : "Add past visits") { navigation.push(.addVisits) }
                .accessibilityIdentifier("gallr-diary-add")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            selectedFilter = publishedInitially ? .publishedNotes : .all
            if surfaceVisible { activate() }
            observation = app.observeDiary { state = $0 }
        }
        .onChange(of: surfaceVisible) { visible in
            if visible { activate() }
        }
        .onDisappear { observation?.cancel() }
    }

    private func activate() {
        app.diary.selectFilter(filter: selectedFilter)
        app.diary.onDiaryShown()
    }

    private func filter(_ title: String, _ choice: DiaryFilter) -> some View {
        Button {
            selectedFilter = choice
            app.diary.selectFilter(filter: choice)
        } label: {
            Text(title)
                .padding(.vertical, 12)
                .overlay(alignment: .bottom) {
                    if selectedFilter == choice {
                        Rectangle().fill(GallrStyle.accent).frame(height: 4)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selectedFilter == choice ? [.isSelected] : [])
    }

    private func entries(_ entries: [DiaryEntry], title: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(.headline).accessibilityAddTraits(.isHeader)
            ForEach(entries, id: \.exhibitionId) { entry in
                Button {
                    navigation.push(.diaryEntry(entry.exhibitionId))
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.snapshot?.localizedName(lang: language)
                             ?? app.findExhibition(id: entry.exhibitionId)?.localizedName(lang: language)
                             ?? (korean ? "전시" : "Exhibition"))
                        if let snapshot = entry.snapshot {
                            Text(snapshot.localizedVenueName(lang: language))
                                .foregroundStyle(GallrStyle.secondary)
                                .font(.footnote)
                        }
                        if let note = entry.visit?.note {
                            Text(note).lineLimit(2).font(.footnote)
                        }
                        if let status = entry.noteStatus, status != .private_ {
                            Text(diaryStatus(status, korean: korean))
                                .font(.footnote)
                                .foregroundStyle(GallrStyle.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("gallr-diary-entry-\(entry.exhibitionId)")
                Divider()
            }
        }
    }

    private func monthTitle(_ month: DiaryMonth) -> String {
        let formatter = DateFormatter()
        let monthName = formatter.monthSymbols[Int(month.month) - 1]
        return "\(monthName) \(month.year)"
    }
}

struct NativeDiaryEntryView: View {
    let app: NativeGallrApp
    let exhibitionID: String
    let focusNote: Bool

    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @Environment(\.gallrNavigationTab) private var ownerTab
    @Environment(\.gallrNavigationEntry) private var navigationEntry
    @State private var viewModel: DiaryEntryViewModel?
    @State private var state: DiaryEntryUiState?
    @State private var exhibition: Exhibition?
    @State private var resolve: NativeObservation?
    @State private var observation: NativeObservation?
    @State private var failed = false
    @State private var startedEditing = false
    @State private var noteDraft = ""
    @State private var confirmation: Confirmation?
    @FocusState private var noteFocused: Bool

    private enum Confirmation: String, Identifiable {
        case delete, discard, removePublic
        var id: String { rawValue }
    }

    private var language: AppLanguage { korean ? .ko : .en }
    private var seoulCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        if let zone = TimeZone(identifier: "Asia/Seoul") { calendar.timeZone = zone }
        return calendar
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(exhibition?.localizedName(lang: language) ?? (korean ? "일기" : "DIARY"))
                    .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
                if let state, let viewModel {
                    if let editor = state.editor {
                        entryForm(editor, model: viewModel)
                    } else if state.isWorking {
                        ProgressView()
                    } else if state.failure != nil {
                        Text(korean ? "기록에 변경 사항을 저장하지 못했어요." : "Couldn’t save the change to your diary.")
                        Button(korean ? "다시 시도" : "Retry") { viewModel.startEditing(focusNote: false) }
                    } else if state.row != nil {
                        NativePrimaryButton(korean ? "방문 기록" : "Record visit") {
                            viewModel.startEditing(focusNote: false)
                        }
                    } else {
                        Text(korean ? "아직 방문을 기록할 수 없어요." : "A visit can’t be recorded yet.")
                    }
                } else if failed {
                    Text(korean ? "기록을 열지 못했어요." : "Couldn’t open this entry.")
                    Button(korean ? "다시 시도" : "Retry", action: load)
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: 550, alignment: .leading)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .environment(\.calendar, seoulCalendar)
        .environment(\.timeZone, seoulCalendar.timeZone)
        .onAppear {
            if let navigationEntry {
                navigation.setBackHandler(for: navigationEntry) {
                    guard state?.editor?.hasUnsavedChanges == true else { return true }
                    confirmation = .discard
                    return false
                }
            }
            load()
        }
        .onDisappear {
            if let navigationEntry { navigation.setBackHandler(for: navigationEntry, handler: nil) }
            resolve?.cancel()
            observation?.cancel()
            if let viewModel { app.releaseViewModel(viewModel: viewModel) }
            viewModel = nil
        }
        .confirmationDialog(
            confirmationTitle,
            isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }),
            titleVisibility: .visible,
            presenting: confirmation
        ) { action in
            Button(korean ? "확인" : "Confirm", role: .destructive) {
                switch action {
                case .delete: viewModel?.delete()
                case .discard:
                    viewModel?.discardEditing()
                    navigation.back(on: ownerTab, bypassHandler: true)
                case .removePublic: viewModel?.save()
                }
            }
            Button(korean ? "취소" : "Cancel", role: .cancel) {}
        }
    }

    private func load() {
        guard viewModel == nil else { return }
        failed = false
        let diary = (app.diary.state.value as? DiaryUiState)?.diary
        let entry = (diary?.months.flatMap(\.entries) ?? []) + (diary?.undated ?? [])
        if let row = entry.first(where: { $0.exhibitionId == exhibitionID }),
           let exhibition = app.diary.exhibitionFor(entry: row) {
            attach(exhibition)
        } else {
            resolve = app.resolveExhibition(id: exhibitionID) { result in
                if let result { attach(result) } else { failed = true }
            }
        }
    }

    private func attach(_ exhibition: Exhibition) {
        self.exhibition = exhibition
        let model = app.diaryEntry(exhibition: exhibition)
        self.viewModel = model
        model.onPageShown()
        observation = app.observeDiaryEntry(viewModel: model) { next in
            let wasEditing = state?.editor != nil
            let wasWorking = state?.isWorking == true
            state = next
            if !wasEditing, let editor = next.editor { noteDraft = editor.note }
            if !startedEditing && next.editor == nil && next.row != nil {
                startedEditing = true
                model.startEditing(focusNote: focusNote)
            }
            if wasWorking && !next.isWorking && next.failure == nil && next.editor == nil {
                navigation.back(on: ownerTab, bypassHandler: true)
            }
            if next.editor?.focusNote == true && !wasEditing { noteFocused = true }
        }
    }

    private func entryForm(_ editor: DiaryEntryEditorState, model: DiaryEntryViewModel) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(korean ? "방문 날짜" : "Visit date").font(.headline)
            if editor.dateOptions.today {
                Button(korean ? "오늘" : "Today") { model.selectDate(date: editor.today) }
            }
            if editor.dateOptions.yesterday {
                Button(korean ? "어제" : "Yesterday") { model.selectDate(date: editor.yesterday) }
            }
            if let visitDate = editor.visitDate.flatMap(toDate) {
                Text(visitDate, format: .dateTime.year().month().day())
                    .foregroundStyle(GallrStyle.secondary)
            }
            if editor.offersDatePicker, let earliest = toDate(editor.dateOptions.earliest),
               let latest = toDate(editor.dateOptions.latest), earliest <= latest {
                DatePicker(korean ? "날짜 선택" : "Choose date", selection: Binding(
                    get: { state?.editor?.visitDate.flatMap(toDate) ?? latest },
                    set: { date in
                        let parts = seoulCalendar.dateComponents([.year, .month, .day], from: date)
                        if let year = parts.year, let month = parts.month, let day = parts.day {
                            model.selectDate(date: Kotlinx_datetimeLocalDate(year: Int32(year), month: Int32(month), day: Int32(day)))
                        }
                    }
                ), in: earliest...latest, displayedComponents: .date)
            }
            Text(korean ? "어땠나요?" : "How was it?").font(.headline)
            ForEach(VisitFeeling.entries, id: \.wire) { feeling in
                selection(feeling.label(language: language), selected: editor.feeling == feeling) {
                    model.toggleFeeling(feeling: feeling)
                }
            }
            Text(korean ? "무엇이 기억에 남았나요?" : "What stood out?").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), alignment: .leading)], alignment: .leading) {
                ForEach(VisitTag.entries, id: \.wire) { tag in
                    selection(tag.label(language: language), selected: editor.tags.contains(tag)) {
                        model.toggleTag(tag: tag)
                    }
                }
            }
            if editor.tagLimitReached {
                Text(korean ? "최대 3개까지 선택할 수 있어요." : "Choose up to three tags.")
                    .font(.footnote)
            }
            Text(korean ? "감상" : "Note").font(.headline)
            TextEditor(text: Binding(
                get: { noteDraft },
                set: { noteDraft = model.updateNoteAndGet(text: $0) }
            ))
            .frame(minHeight: 110)
            .focused($noteFocused)
            .overlay(Rectangle().stroke(GallrStyle.divider, lineWidth: 1).allowsHitTesting(false))
            .accessibilityLabel(korean ? "감상" : "Note")
            .accessibilityIdentifier("gallr-diary-note")
            Text(korean ? "누가 볼 수 있나요?" : "Who can see this?").font(.headline)
            selection(korean ? "나만 보기" : "Only me", selected: editor.shownVisibility == .onlyMe) {
                model.selectVisibility(visibility: .onlyMe)
            }
            selection(korean ? "전시 페이지에 공개" : "Public on the exhibition page",
                      selected: editor.shownVisibility == .exhibitionPage) {
                model.selectVisibility(visibility: .exhibitionPage)
            }
            .disabled(!editor.publicChoiceEnabled)
            if !editor.isSignedIn {
                Button(korean ? "로그인" : "Sign in") { navigation.push(.promptedSignIn) }
            }
            if let status = editor.noteStatus {
                Text(diaryStatus(status, korean: korean)).foregroundStyle(GallrStyle.secondary)
            }
            if editor.offersAskAgainWithEdit || editor.offersEditAndAskAgain {
                Button(korean ? "수정한 내용으로 다시 요청" : "Submit the edited note again") {
                    model.askAgain()
                    noteFocused = true
                }
            }
            NativePrimaryButton(korean ? "저장" : "Save", enabled: editor.canSave && state?.isWorking == false) {
                if editor.savingRemovesPublicNote {
                    confirmation = .removePublic
                } else {
                    model.save()
                }
            }
            .accessibilityIdentifier("gallr-diary-save")
            if editor.isExistingEntry {
                Button(korean ? "기록 삭제" : "Delete entry", role: .destructive) { confirmation = .delete }
            }
            Button(korean ? "취소" : "Cancel") {
                if editor.hasUnsavedChanges {
                    confirmation = .discard
                } else {
                    model.discardEditing()
                    navigation.back(on: ownerTab)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func selection(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .padding(.horizontal, 8)
                .frame(minHeight: 44)
                .overlay(Rectangle().stroke(selected ? GallrStyle.accent : GallrStyle.divider, lineWidth: 1))
        }
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func toDate(_ date: Kotlinx_datetimeLocalDate) -> Date? {
        seoulCalendar.date(from: DateComponents(year: Int(date.year), month: Int(date.month.ordinal) + 1, day: Int(date.day)))
    }

    private var confirmationTitle: String {
        switch confirmation {
        case .delete: return korean ? "일기에서 이 기록을 삭제할까요?" : "Delete this diary entry?"
        case .discard: return korean ? "변경 내용을 버릴까요?" : "Discard your changes?"
        case .removePublic: return korean ? "공개 감상을 삭제할까요?" : "Remove the public note?"
        case nil: return ""
        }
    }
}

private func diaryStatus(_ status: PublicNoteStatus, korean: Bool) -> String {
    switch status {
    case .inReview: return korean ? "검토 중" : "In review"
    case .published: return korean ? "공개됨" : "Published"
    case .notPublished: return korean ? "공개되지 않았어요" : "Not published"
    case .sending: return korean ? "동기화 중" : "Syncing"
    case .retry: return korean ? "전송하지 못했어요" : "Couldn’t send"
    default: return korean ? "나만 보기" : "Only me"
    }
}
