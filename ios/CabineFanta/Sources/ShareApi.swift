import Foundation

enum ShareApi {
    static let shareOrigin = "https://fanta-filtro.seuprojeto.online"

    static func uploadPolaroid(_ jpeg: Data, fileName: String) async throws {
        let url = URL(string: shareOrigin.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/upload_polaroid")!
        let boundary = "Fanta\(Int(Date().timeIntervalSince1970 * 1000))"

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        func append(_ string: String) { body.append(Data(string.utf8)) }

        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n\(fileName)\r\n")
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"\(fileName)\"\r\nContent-Type: image/jpeg\r\n\r\n")
        body.append(jpeg)
        append("\r\n--\(boundary)--\r\n")

        request.httpBody = body

        let (_, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code < 200 || code >= 300 {
            throw URLError(.badServerResponse)
        }
    }
}
