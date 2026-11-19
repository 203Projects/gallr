import PhotosUI
import SwiftUI
import composeApp

struct NativeAccountView: View {
    let app: NativeGallrApp
    let prompted: Bool
    let recovery: Bool

    @State private var auth: AuthState?
    @State private var observation: NativeObservation?

    var body: some View {
        Group {
            if let authenticated = auth as? AuthState.Authenticated, !recovery {
                NativeProfileView(app: app, user: authenticated.user)
                    .id(authenticated.user.id)
            } else if auth is AuthState.Anonymous || recovery {
                NativeSignInView(app: app, recovery: recovery)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .onAppear { observation = app.observeAuth { auth = $0 } }
        .onDisappear {
            observation?.cancel()
            if prompted { app.signIn.clearSensitiveState() }
        }
    }
}

private struct NativeSignInView: View {
    let app: NativeGallrApp
    let recovery: Bool

    @Environment(\.gallrKorean) private var korean
    @State private var state: SignInUiState?
    @State private var observation: NativeObservation?
    @State private var showPassword = false
    @State private var inputEmail = ""
    @State private var inputPassword = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("gallr")
                    .font(GallrStyle.font(40, weight: .bold, relativeTo: .largeTitle))
                    .accessibilityAddTraits(.isHeader)
                Text(korean ? "취향으로 발견하는 전시" : "discover exhibitions through taste")
                    .foregroundStyle(GallrStyle.secondary)
                if let state {
                    switch state.mode {
                    case .verificationSent, .resetSent:
                        sent(state)
                    default:
                        fields(state)
                    }
                    if let error = state.error {
                        Text(message(for: error))
                            .foregroundStyle(GallrStyle.secondary)
                            .accessibilityIdentifier("gallr-sign-in-error")
                    }
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: 460)
            .padding(32)
            .frame(maxWidth: .infinity)
        }
        .onAppear {
            if recovery { app.signIn.showForgotPassword() }
            observation = app.observeSignIn { next in
                if state?.mode != next.mode {
                    inputEmail = next.email
                    inputPassword = next.password
                }
                state = next
            }
        }
        .onDisappear { observation?.cancel() }
    }

    private func fields(_ state: SignInUiState) -> some View {
        let forgot = state.mode == .forgotPassword
        let email = Binding(get: { inputEmail }, set: {
            inputEmail = $0
            app.signIn.updateEmail(email: $0)
        })
        let password = Binding(get: { inputPassword }, set: {
            inputPassword = $0
            app.signIn.updatePassword(password: $0)
        })

        return VStack(alignment: .leading, spacing: 16) {
            TextField(korean ? "이메일" : "Email", text: email)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .modifier(NativeFieldStyle())
                .accessibilityIdentifier("gallr-sign-in-email")
            if !forgot {
                HStack {
                    Group {
                        if showPassword {
                            TextField(korean ? "비밀번호" : "Password", text: password)
                        } else {
                            SecureField(korean ? "비밀번호" : "Password", text: password)
                        }
                    }
                    .textContentType(state.mode == .signUp ? .newPassword : .password)
                    .accessibilityIdentifier("gallr-sign-in-password")
                    Button {
                        showPassword.toggle()
                    } label: {
                        Image(systemName: showPassword ? "eye.slash" : "eye")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel(showPassword
                        ? (korean ? "비밀번호 숨기기" : "Hide password")
                        : (korean ? "비밀번호 보기" : "Show password"))
                }
                .modifier(NativeFieldStyle())
            }
            NativePrimaryButton(submitTitle(state.mode), enabled: !state.isLoading, action: app.signIn.submit)
                .accessibilityIdentifier("gallr-sign-in-submit")
            if state.isLoading { ProgressView() }
            if forgot {
                Button(korean ? "로그인으로 돌아가기" : "Back to sign in", action: app.signIn.backToSignIn)
            } else {
                Button(state.mode == .signUp
                    ? (korean ? "이미 계정이 있나요? 로그인" : "Already have an account? Sign in")
                    : (korean ? "계정이 없나요? 가입하기" : "Don’t have an account? Sign up"),
                    action: app.signIn.toggleSignInMode)
                Button(korean ? "비밀번호를 잊으셨나요?" : "Forgot password?", action: app.signIn.showForgotPassword)
                Divider()
                NativePrimaryButton(korean ? "Google로 계속하기" : "Continue with Google", enabled: !state.isLoading) {
                    app.signIn.signInWithOAuth(provider: .google)
                }
                NativePrimaryButton(korean ? "Apple로 계속하기" : "Continue with Apple", enabled: !state.isLoading) {
                    app.signIn.signInWithOAuth(provider: .apple)
                }
            }
        }
    }

    private func sent(_ state: SignInUiState) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(state.mode == .resetSent
                ? (korean ? "비밀번호 재설정 메일을 보냈어요." : "Password reset email sent.")
                : (korean ? "인증 메일을 보냈어요." : "Verification email sent."))
                .font(.headline)
            Text(state.verifiedEmail)
            NativePrimaryButton(korean ? "다시 보내기" : "Resend", enabled: !state.isLoading, action: app.signIn.resend)
            Button(korean ? "로그인으로 돌아가기" : "Back to sign in", action: app.signIn.backToSignIn)
        }
    }

    private func submitTitle(_ mode: SignInMode) -> String {
        if mode == .forgotPassword { return korean ? "재설정 메일 보내기" : "Send reset email" }
        if mode == .signUp { return korean ? "가입하기" : "Sign up" }
        return korean ? "로그인" : "Sign in"
    }

    private func message(for error: SignInError) -> String {
        switch error {
        case .invalidEmail: return korean ? "이메일 주소를 확인해 주세요." : "Check your email address."
        case .passwordTooShort: return korean ? "비밀번호는 6자 이상이어야 해요." : "Use at least 6 characters."
        case .invalidCredentials: return korean ? "이메일이나 비밀번호를 확인해 주세요." : "Check your email and password."
        case .emailNotConfirmed: return korean ? "이메일 인증을 완료해 주세요." : "Verify your email address."
        case .emailAlreadyRegistered: return korean ? "이미 가입된 이메일이에요." : "This email is already registered."
        case .rateLimited: return korean ? "잠시 후 다시 시도해 주세요." : "Try again in a moment."
        case .signupsDisabled: return korean ? "지금은 새 계정을 만들 수 없어요." : "New accounts are unavailable right now."
        default: return korean ? "다시 시도해 주세요." : "Something went wrong. Try again."
        }
    }
}

private struct NativeProfileView: View {
    let app: NativeGallrApp
    let user: GallrUser

    @EnvironmentObject private var navigation: GallrNavigation
    @Environment(\.gallrKorean) private var korean
    @State private var profile: ProfileUiState?
    @State private var observation: NativeObservation?

    @State private var viewModel: ProfileViewModel?
    @State private var editing: ProfileEditor?

    private struct ProfileEditor: Identifiable {
        let id = UUID()
        let model: EditProfileViewModel
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                NativeAvatar(url: profile?.profile?.avatarUrl ?? user.avatarUrl)
                Text(profile?.profile?.displayName ?? user.displayName)
                    .font(GallrStyle.font(24, weight: .bold, korean: korean, relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
                if let bio = profile?.profile?.bio, !bio.isEmpty { Text(bio) }
                Button(korean ? "프로필 편집" : "Edit profile") {
                    editing = ProfileEditor(model: app.editProfile(user: user, profile: profile?.profile))
                }
                Button(korean ? "감상 \(profile?.publicNoteCount ?? 0)" : "Notes \(profile?.publicNoteCount ?? 0)") {
                    navigation.open(.publishedDiary, on: .my)
                }
                if profile?.loadFailed == true {
                    Button(korean ? "다시 시도" : "Retry") { viewModel?.refresh() }
                }
            }
            .frame(maxWidth: 460, alignment: .leading)
            .padding(32)
            .frame(maxWidth: .infinity)
        }
        .onAppear {
            let model = app.profile(user: user)
            viewModel = model
            observation = app.observeProfile(viewModel: model) { profile = $0 }
        }
        .onDisappear {
            observation?.cancel()
            if let viewModel { app.releaseViewModel(viewModel: viewModel) }
            viewModel = nil
        }
        .sheet(item: $editing, onDismiss: { viewModel?.refresh() }) { editor in
            NativeEditProfileView(app: app, viewModel: editor.model)
        }
    }
}

private struct NativeEditProfileView: View {
    let app: NativeGallrApp
    let viewModel: EditProfileViewModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.gallrKorean) private var korean
    @State private var state: EditProfileUiState?
    @State private var observation: NativeObservation?
    @State private var photo: PhotosPickerItem?
    @State private var cropSelection: AvatarCropSelection?
    @State private var inputDisplayName = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    PhotosPicker(selection: $photo, matching: .images) {
                        NativeAvatar(url: state?.avatarUrl)
                    }
                    .accessibilityLabel(korean ? "프로필 사진 변경" : "Change profile photo")
                    TextField(korean ? "닉네임" : "Display name", text: Binding(
                        get: { inputDisplayName },
                        set: {
                            inputDisplayName = $0
                            viewModel.updateDisplayName(displayName: $0)
                        }
                    ))
                    .modifier(NativeFieldStyle())
                    if state?.error != nil {
                        Text(korean ? "변경을 저장하지 못했어요." : "Couldn’t save your changes.")
                    }
                    NativePrimaryButton(korean ? "저장" : "Save", enabled: state?.isSaving == false && state?.isUploadingAvatar == false) {
                        viewModel.saveProfile()
                    }
                }
                .padding(24)
            }
            .navigationTitle(korean ? "프로필 편집" : "Edit profile")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(korean ? "닫기" : "Close") { dismiss() }
                }
            }
        }
        .onAppear {
            observation = app.observeEditProfile(viewModel: viewModel) { next in
                if state == nil { inputDisplayName = next.displayName }
                state = next
                if next.saveSucceeded {
                    viewModel.consumeSaveSuccess()
                    dismiss()
                }
            }
        }
        .onDisappear {
            observation?.cancel()
            app.releaseViewModel(viewModel: viewModel)
        }
        .task(id: photo) {
            guard let photo else { return }
            guard let data = try? await photo.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
                viewModel.showError(error: .imageReadFailed)
                return
            }
            guard min(image.size.width, image.size.height) > 0 else {
                viewModel.showError(error: .imageProcessingFailed)
                return
            }
            cropSelection = AvatarCropSelection(image: image)
        }
        .sheet(item: $cropSelection, onDismiss: { photo = nil }) { selection in
            NativeAvatarCropView(image: selection.image, onSave: upload)
        }
    }

    private func upload(_ image: UIImage) {
        guard let output = image.jpegData(compressionQuality: 0.85) else {
            viewModel.showError(error: .imageProcessingFailed)
            return
        }
        let bytes = KotlinByteArray(size: Int32(output.count))
        for (index, byte) in output.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        viewModel.uploadAvatar(imageBytes: bytes)
    }
}

private struct AvatarCropSelection: Identifiable {
    let id = UUID()
    let image: UIImage
}

private struct NativeAvatarCropView: View {
    let image: UIImage
    let onSave: (UIImage) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.gallrKorean) private var korean
    @State private var pan: CGSize = .zero
    @State private var zoom: CGFloat = 1
    @GestureState private var dragging = CGSize.zero
    @GestureState private var magnification: CGFloat = 1

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    GeometryReader { geometry in
                        preview(side: geometry.size.width)
                    }
                    .aspectRatio(1, contentMode: .fit)
                    Slider(value: $zoom, in: 1...4)
                        .accessibilityLabel(korean ? "사진 확대" : "Image zoom")
                    NativePrimaryButton(korean ? "사용하기" : "Use photo") {
                        onSave(croppedImage())
                        dismiss()
                    }
                }
                .frame(maxWidth: 440)
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle(korean ? "사진 자르기" : "Crop photo")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(korean ? "취소" : "Cancel") { dismiss() }
                }
            }
        }
    }

    private func preview(side: CGFloat) -> some View {
        let scale = min(max(zoom * magnification, 1), 4)
        let translation = clamped(CGSize(
            width: pan.width + dragging.width / max(side, 1),
            height: pan.height + dragging.height / max(side, 1)
        ), at: scale)
        let size = positionedSize(at: scale)

        return Image(uiImage: image)
            .resizable()
            .frame(width: size.width * side, height: size.height * side)
            .offset(x: translation.width * side, y: translation.height * side)
            .frame(width: side, height: side)
            .clipped()
            .contentShape(Rectangle())
            .gesture(DragGesture()
                .updating($dragging) { value, state, _ in state = value.translation }
                .onEnded { value in
                    pan = clamped(CGSize(
                        width: pan.width + value.translation.width / max(side, 1),
                        height: pan.height + value.translation.height / max(side, 1)
                    ), at: zoom)
                }
            )
            .simultaneousGesture(MagnificationGesture()
                .updating($magnification) { value, state, _ in state = value }
                .onEnded { value in
                    zoom = min(max(zoom * value, 1), 4)
                    pan = clamped(pan, at: zoom)
                }
            )
            .accessibilityLabel(korean ? "자른 사진 미리보기" : "Crop preview")
    }

    private func positionedSize(at scale: CGFloat) -> CGSize {
        let naturalSide = min(image.size.width, image.size.height)
        return CGSize(width: image.size.width * scale / naturalSide, height: image.size.height * scale / naturalSide)
    }

    private func clamped(_ offset: CGSize, at scale: CGFloat) -> CGSize {
        let size = positionedSize(at: scale)
        let x = (size.width - 1) / 2
        let y = (size.height - 1) / 2
        return CGSize(width: min(max(offset.width, -x), x), height: min(max(offset.height, -y), y))
    }

    private func croppedImage() -> UIImage {
        let side: CGFloat = 512
        let size = positionedSize(at: zoom)
        let offset = clamped(pan, at: zoom)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            image.draw(in: CGRect(
                x: (1 - size.width) * side / 2 + offset.width * side,
                y: (1 - size.height) * side / 2 + offset.height * side,
                width: size.width * side,
                height: size.height * side
            ))
        }
    }
}

struct NativeAvatar: View {
    let url: String?

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Image(systemName: "person.fill")
                .foregroundStyle(GallrStyle.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(GallrStyle.mutedSurface)
        }
        .frame(width: 64, height: 64)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

struct NativePrimaryButton: View {
    let title: String
    var enabled: Bool
    let action: () -> Void

    init(_ title: String, enabled: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .frame(maxWidth: .infinity, minHeight: 52)
                .foregroundStyle(GallrStyle.background)
                .background(enabled ? GallrStyle.foreground : GallrStyle.secondary)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

struct NativeFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 12)
            .frame(minHeight: 52)
            .overlay(Rectangle().stroke(GallrStyle.divider, lineWidth: 1).allowsHitTesting(false))
    }
}
