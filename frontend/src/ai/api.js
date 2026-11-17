import { apiRequest } from "../api/client.js";

export async function proofreadGroupDescription(text) {
    return apiRequest('/api/v1/ai/proofread', {
        method: 'POST',
        body: {text}
    });
}