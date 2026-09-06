/// Generic Result type for robust error handling without exceptions
sealed class Result<S, E> {
  const Result();

  bool get isSuccess => this is Success<S, E>;
  bool get isFailure => this is Failure<S, E>;

  S? get successOrNull => switch (this) {
        Success(value: final v) => v,
        Failure() => null,
      };

  E? get failureOrNull => switch (this) {
        Success() => null,
        Failure(error: final e) => e,
      };

  R fold<R>(R Function(S success) onSuccess, R Function(E error) onFailure) {
    return switch (this) {
      Success(value: final v) => onSuccess(v),
      Failure(error: final e) => onFailure(e),
    };
  }
}

final class Success<S, E> extends Result<S, E> {
  final S value;
  const Success(this.value);
}

final class Failure<S, E> extends Result<S, E> {
  final E error;
  const Failure(this.error);
}

/// Generic application error representation
class AppError {
  final String message;
  final String? code;
  final Object? cause;

  const AppError({
    required this.message,
    this.code,
    this.cause,
  });

  @override
  String toString() => 'AppError(code: $code, message: $message)';
}
