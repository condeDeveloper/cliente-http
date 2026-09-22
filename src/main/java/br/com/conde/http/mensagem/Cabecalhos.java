package br.com.conde.http.mensagem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Os cabeçalhos de uma mensagem HTTP.
 *
 * <p>Duas regras do protocolo que uma implementação apressada quebra:
 *
 * <ol>
 *   <li><b>O nome não diferencia maiúscula de minúscula.</b> {@code Content-Type},
 *       {@code content-type} e {@code CONTENT-TYPE} são o mesmo cabeçalho.
 *       Guardar num mapa comum faz o cliente ignorar a resposta de metade dos
 *       servidores do mundo.
 *   <li><b>O mesmo nome pode aparecer várias vezes.</b> É assim que vêm vários
 *       {@code Set-Cookie}, e um mapa de nome para valor único perde todos
 *       menos o último.
 * </ol>
 *
 * <p>A ordem de entrada é preservada porque ela importa em depuração e em
 * comparação byte a byte com o que o servidor mandou.
 */
public final class Cabecalhos {

  private final Map<String, List<String>> porNomeNormalizado = new LinkedHashMap<>();
  private final Map<String, String> nomeOriginal = new LinkedHashMap<>();

  /** Acrescenta sem tirar o que já existe. */
  public Cabecalhos acrescentar(String nome, String valor) {
    String chave = normalizar(nome);

    porNomeNormalizado.computeIfAbsent(chave, ignorado -> new ArrayList<>()).add(valor);
    nomeOriginal.putIfAbsent(chave, nome);

    return this;
  }

  /** Substitui tudo que havia com este nome. */
  public Cabecalhos definir(String nome, String valor) {
    String chave = normalizar(nome);

    porNomeNormalizado.put(chave, new ArrayList<>(List.of(valor)));
    nomeOriginal.put(chave, nome);

    return this;
  }

  /** Remove o cabeçalho. */
  public Cabecalhos remover(String nome) {
    String chave = normalizar(nome);

    porNomeNormalizado.remove(chave);
    nomeOriginal.remove(chave);

    return this;
  }

  /** O primeiro valor, se houver. */
  public Optional<String> primeiro(String nome) {
    List<String> valores = porNomeNormalizado.get(normalizar(nome));

    return valores == null || valores.isEmpty() ? Optional.empty() : Optional.of(valores.get(0));
  }

  /** Todos os valores deste nome. */
  public List<String> todos(String nome) {
    return List.copyOf(porNomeNormalizado.getOrDefault(normalizar(nome), List.of()));
  }

  public boolean tem(String nome) {
    return porNomeNormalizado.containsKey(normalizar(nome));
  }

  /** Indica se o cabeçalho tem este valor, sem ligar para maiúsculas. */
  public boolean valeComo(String nome, String valor) {
    return todos(nome).stream().anyMatch(atual -> atual.trim().equalsIgnoreCase(valor));
  }

  /** Os nomes, na ordem em que entraram, com a grafia original. */
  public List<String> nomes() {
    return List.copyOf(nomeOriginal.values());
  }

  public int quantidade() {
    return porNomeNormalizado.values().stream().mapToInt(List::size).sum();
  }

  public boolean vazio() {
    return porNomeNormalizado.isEmpty();
  }

  /** Escreve como vai no fio, com CRLF no fim de cada linha. */
  public String comoTexto() {
    StringBuilder texto = new StringBuilder();

    for (Map.Entry<String, List<String>> entrada : porNomeNormalizado.entrySet()) {
      String nome = nomeOriginal.get(entrada.getKey());

      for (String valor : entrada.getValue()) {
        texto.append(nome).append(": ").append(valor).append("\r\n");
      }
    }

    return texto.toString();
  }

  /**
   * Lê uma linha de cabeçalho.
   *
   * <p>Só o <b>primeiro</b> dois-pontos separa: um {@code Location} com
   * {@code https://…} tem outro logo ali, e cortar no último quebraria a URL.
   */
  public static String[] partir(String linha) {
    int corte = linha.indexOf(':');

    if (corte <= 0) {
      throw new ErroDeHttp("Linha de cabeçalho sem nome: " + linha);
    }

    return new String[] {linha.substring(0, corte).trim(), linha.substring(corte + 1).trim()};
  }

  private static String normalizar(String nome) {
    return nome.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return comoTexto();
  }
}
