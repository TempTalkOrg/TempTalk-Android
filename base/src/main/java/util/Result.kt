package util




sealed class Result<out S, out F> {

  data class Failure<out F>(val failure: F) : Result<Nothing, F>()
  data class Success<out S>(val success: S) : Result<S, Nothing>()

  companion object {
    @JvmStatic
    fun <S> success(value: S) = Success(value)

    @JvmStatic
    fun <F> failure(value: F) = Failure(value)
  }

  



  fun <T> map(onSuccess: (S) -> T): Result<T, F> {
    return when (this) {
      is Failure -> this
      is Success -> success(onSuccess(success))
    }
  }

  



  fun <T> either(
    onSuccess: (S) -> T,
    onFailure: (F) -> T
  ): T {
    return when (this) {
      is Success -> onSuccess(success)
      is Failure -> onFailure(failure)
    }
  }
}







fun <T, S, F> Result<S, F>.flatMap(onSuccess: (S) -> Result<T, F>): Result<T, F> {
  return when (this) {
    is Result.Success -> onSuccess(success)
    is Result.Failure -> this
  }
}




typealias Try<S> = Result<S, Throwable>
