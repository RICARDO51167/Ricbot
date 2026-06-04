export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    let message = `GET ${path} failed`;
    try {
      const json = await response.json() as { error?: { message?: string } };
      message = json.error?.message || message;
    } catch {
      // Keep the generic message when the backend does not return JSON.
    }
    throw new ApiError(message, response.status);
  }

  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) {
    throw new ApiError(`GET ${path} did not return JSON`, response.status);
  }

  return response.json() as Promise<T>;
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body ?? {}),
  });

  if (!response.ok) {
    let message = `POST ${path} failed`;
    try {
      const json = await response.json() as { error?: { message?: string } };
      message = json.error?.message || message;
    } catch {
      // Keep the generic message when the backend does not return JSON.
    }
    throw new ApiError(message, response.status);
  }

  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) {
    throw new ApiError(`POST ${path} did not return JSON`, response.status);
  }

  return response.json() as Promise<T>;
}
