import SwiftUI

struct OnboardingView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color.black, Color(red: 0.06, green: 0.08, blue: 0.16, opacity: 1)],
                startPoint: .top,
                endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 22) {
                    Spacer(minLength: 40)
                    AppBadge(size: 92)
                    Text("HandHack")
                        .font(.largeTitle.bold())
                        .foregroundStyle(.white)
                    Text("Проверка стойкости паролей Wi-Fi по вашим собственным записям рукопожатия")
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white.opacity(0.75))
                        .padding(.horizontal, 24)

                    VStack(alignment: .leading, spacing: 16) {
                        point("lock.shield", "Только ваши сети",
                              "Приложение проверяет пароли к записям, которые вы сами загрузили.")
                        point("iphone.and.arrow.forward", "Всё считается на устройстве",
                              "Никаких серверов и отправки данных — словарь и расчёт остаются в телефоне.")
                        point("checkmark.seal", "Без взлома чужих сетей",
                              "Используйте только на оборудовании, которым владеете или имеете право управлять.")
                    }
                    .padding(.horizontal)

                    Button {
                        dismiss()
                    } label: {
                        Text("Продолжить")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppTheme.accent)
                    .padding(.horizontal)
                    .padding(.top, 8)

                    Text("@primenimooo")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.5))
                    Spacer(minLength: 20)
                }
            }
        }
    }

    private func point(_ symbol: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).foregroundStyle(.white)
                Text(text).font(.subheadline).foregroundStyle(.white.opacity(0.7))
            }
        }
        .glassCard()
    }
}