package br.com.conde.http.mensagem;

/** A mensagem HTTP não está no formato esperado. */
public class ErroDeHttp extends RuntimeException {

  public ErroDeHttp(String mensagem) {
    super(mensagem);
  }

  public ErroDeHttp(String mensagem, Throwable causa) {
    super(mensagem, causa);
  }
}
